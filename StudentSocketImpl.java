import java.net.*;
import java.io.*;
import java.util.Timer;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  // protected InetAddress address;
  // protected int port;
  // protected int localport;

  private Demultiplexer D;
  private Timer tcpTimer;

  // enum to track all possible states of TCP FSM
  private enum states {
    CLOSED, LISTEN, SYN_SENT, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, CLOSING, CLOSE_WAIT, LAST_ACK, TIME_WAIT
  }

  // current state
  private states currState = states.CLOSED;
  private int seqNum = 0;
  private int ackNum = 0;

  StudentSocketImpl(Demultiplexer D) { // default constructor
    this.D = D;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param address the IP address of the remote host.
   * @param port    the port number.
   * @exception IOException if an I/O error occurs when attempting a connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException {
    localport = D.getNextAvailablePort();
    this.address = address;
    this.port = port;
    D.registerConnection(address, localport, port, this);
    TCPWrapper.setUDPPortNumber(port);
    TCPWrapper.send(new TCPPacket(localport, port, seqNum, ackNum, false, true, false, 50, null), address);
    changeState(states.SYN_SENT);
    while (currState != states.ESTABLISHED) {
      try {
        wait(50);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * Changes state and handles the final socket closing
   * @param newState an enum representing state in the TCP FSM
   * @throws IOException if unregistering the socket goes awry
   */
  private synchronized void changeState(states newState) throws IOException{
    System.out.println("!!! "+ currState + " -> " + newState);
    currState = newState;
    if(newState == states.TIME_WAIT){
      createTimerTask(30000, new Object());
      D.unregisterConnection(address, localport, port, this);
      changeState(states.CLOSED);  
    }
  }

  
  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){
    this.notifyAll();
    try{
      switch(currState){
        case LISTEN:
          this.address = p.sourceAddr;
          this.port = p.sourcePort;
          this.seqNum = p.ackNum;
          this.ackNum = p.seqNum + 1;
          D.unregisterListeningSocket(localport, this);
          D.registerConnection(address, localport, port, this);
          TCPWrapper.send(new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 50, null), address);
          changeState(states.SYN_RCVD);
          break;
        case SYN_SENT:
          TCPWrapper.send(new TCPPacket(localport, port, seqNum + 1, ackNum, true, false, false, 50, null), address);
          changeState(states.ESTABLISHED);
          break;
        case SYN_RCVD:
          changeState(states.ESTABLISHED);
          break;
        case ESTABLISHED:
          if(p.finFlag){
            TCPWrapper.send(new TCPPacket(localport, port, seqNum, p.ackNum, true, false, false, 50, null), address);
            changeState(states.CLOSE_WAIT);
          }
          break;
        case FIN_WAIT_1:
          if(p.finFlag){
            changeState(states.CLOSING);
            TCPWrapper.send(new TCPPacket(localport, port, seqNum, p.ackNum, true, false, false, 50, null), address);
          } else if (p.ackFlag){
            changeState(states.FIN_WAIT_2);
          }
          break;
        case CLOSING: case LAST_ACK:
          changeState(states.TIME_WAIT);
          break;
        case FIN_WAIT_2:
          TCPWrapper.send(new TCPPacket(localport, port, seqNum, p.ackNum, true, false, false, 50, null), address);
          changeState(states.TIME_WAIT);
        default:


      }
  
    } catch (IOException e){
      System.out.println(e);
    }
  }
  
  /** 
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling 
   * ServerSocket.accept(), but this method belongs to the Socket object 
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
    D.registerListeningSocket(localport, this);
    changeState(states.LISTEN);
    while (currState != states.ESTABLISHED) {
      try {
        wait(50);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
  }

  
  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
    
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an 
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }


  /**
   * Closes this socket. 
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
    if(currState == states.ESTABLISHED)
      changeState(states.FIN_WAIT_1);
    else if(currState == states.CLOSE_WAIT)
      changeState(states.LAST_ACK);
    else // nothing should happen if not in either state
      return;
    TCPWrapper.send(new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 50, null), address);
    createTimerTask(10*1000, new Object());
  }

  /** 
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    System.out.println("Timer task called, state = " + currState + " len = " + delay);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return 
   * information.
   */
  public synchronized void handleTimer(Object ref){

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
    System.out.println("Timer task ended, state = " + currState);

  }
}
