package com.github.pangolin.tun.net;

import java.net.SocketException;
import java.util.List;

/**
 */
public interface NetworkInterfaceEx {

  List<InterfaceAddressEx> getInterfaceAddresses();

  void setInterfaceAddress(InterfaceAddressEx address);

  void addInterfaceAddress(InterfaceAddressEx address);

  void deleteInterfaceAddress(InterfaceAddressEx address);

  void flushInterfaceAddresses();

  int getMTU() throws SocketException;
}
