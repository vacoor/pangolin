package com.github.pangolin.routing.acceptor.tun.net.handler.tcp.internal;

/**
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp_states.h">tcp_states.h</a>
 * @see <a href="https://github.com/torvalds/linux/blob/master/include/net/tcp.c">tcp.c</a>
 */
public enum TcpState {

  /**
   * connection established.
   */
  TCP_ESTABLISHED,

  /**
   * sent a connection request, waiting for ack.
   */
  TCP_SYN_SENT,

  /**
   * received a connection request, sent ack, waiting for final ack in three-way handshake.
   */
  TCP_SYN_RECV,

  /**
   * our side has shutdown, waiting to complete transmission of remaining buffered data.
   */
  TCP_FIN_WAIT1,

  /**
   * all buffered data sent, waiting for remote to shutdown.
   */
  TCP_FIN_WAIT2,

  /**
   * timeout to catch resent junk before entering closed, can only be entered from FIN_WAIT2 or CLOSING.  Required because the other end may not have
   * gotten our last ACK causing it to retransmit the data packet (which we ignore).
   */
  TCP_TIME_WAIT,

  /**
   * socket is finished.
   */
  TCP_CLOSE,

  /**
   * remote side has shutdown and is waiting for us to finish writing our data and to shutdown (we have to close() to move on to LAST_ACK).
   */
  TCP_CLOSE_WAIT,

  /**
   * out side has shutdown after remote has shutdown.  There may still be data in our buffer that we have to finish sending.
   */
  TCP_LAST_ACK,

  TCP_LISTEN,

  /**
   * both sides have shutdown but we still have data we have to finish sending.
   */
  TCP_CLOSING,


  TCP_NEW_SYN_RECV,

  TCP_BOUND_INACTIVE,

  TCP_MAX_STATES

}
