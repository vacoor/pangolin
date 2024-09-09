package com.github.pangolin.routing.beta;

import java.net.SocketException;
import java.util.List;

/**
 */
public interface NetworkInterfaceEx {

  List<InterfaceAddressEx> getInterfaceAddresses();

  void setInterfaceAddress(InterfaceAddressEx address);

  void flushInterfaceAddresses();

  int getMTU() throws SocketException;
}
