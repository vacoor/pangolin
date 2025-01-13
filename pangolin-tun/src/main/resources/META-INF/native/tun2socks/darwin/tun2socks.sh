# https://github.com/eycorsican/leaf/blob/master/leaf/src/sys.rs
# https://github.com/eycorsican/leaf/blob/master/leaf/src/common/cmd_macos.rs
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/main.js#L702
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/helper/win32/config_route.bat

# In macOS, we need to start tun2socks first so that it will create TUN interface for us.
# In this example, "en0" is the default primary network interface.
function tun_create() {
    # sudo ./tun2socks-darwin-amd64-v3 -device utun123 -proxy socks5://192.168.1.201:1081 -interface en0
cat > .tun_post_up.sh << EOF
#/bin/sh
source $0 env
tun_start
EOF
   chmod +x .tun_post_up.sh
    sudo ./tun2socks-darwin-amd64-v3 -device utun123 -proxy socks5://192.168.1.201:1081 -interface en0 -tun-post-up 'sh -c "./.tun_post_up.sh"'
    rm -rf .tun_post_up.sh
}

function tun_start() {
   # Use ifconfig to bring the TUN interface up and assign addresses for it.
   sudo ifconfig utun123 inet 198.18.0.1 netmask 255.255.0.0 198.18.0.1 up
   # sudo ifconfig utun123 inet 198.18.0.1 netmask 255.255.0.0 198.18.0.1

   # Add TUN inteface route.

   # Mode 1: Add these specific routes so that tun2socks can handle primary connections.
   # sudo route add -net 1.0.0.0/8 198.18.0.1
   # sudo route add -net 2.0.0.0/7 198.18.0.1
   # sudo route add -net 4.0.0.0/6 198.18.0.1
   # sudo route add -net 8.0.0.0/5 198.18.0.1
   # sudo route add -net 16.0.0.0/4 198.18.0.1
   # sudo route add -net 32.0.0.0/3 198.18.0.1
   # sudo route add -net 64.0.0.0/2 198.18.0.1
   # sudo route add -net 128.0.0.0/1 198.18.0.1
    sudo route add -net 198.18.0.0/15 198.18.0.1
   sudo route add -net 10.188.0.0/15 198.18.0.1

   # Mode 2: Route default traffic to TUN interface.
   # sudo route delete -inet default
   # sudo route delete -inet default -ifscope en0
   # sudo route add -inet default 198.18.0.1
   # sudo route add -inet default 192.168.1.1 -ifscope en0

   # Set DNS servers
   # networksetup -listnetworkserviceorder | grep 'Hardware Port'
   networksetup -setdnsservers "Wi-Fi" 192.168.1.201 192.168.1.1
   # networksetup -setdnsservers "Wi-Fi" 192.168.1.1

   # Flush dns cache
   sudo killall -HUP mDNSResponder;
}



function tun_stop() {
   # Cleanup TUN interface route.
   # Mode 1:
   # sudo route delete -net 1.0.0.0/8
   # sudo route delete -net 2.0.0.0/7
   # sudo route delete -net 4.0.0.0/6
   # sudo route delete -net 8.0.0.0/5
   # sudo route delete -net 16.0.0.0/4
   # sudo route delete -net 32.0.0.0/3
   # sudo route delete -net 64.0.0.0/2
   # sudo route delete -net 128.0.0.0/1
    sudo route delete -net 198.18.0.0/15
   sudo route delete -net 10.188.0.0/15

   # Mode 2: Restore default routes
   # sudo route delete -inet default
   # sudo route delete -inet default -ifscope en0
   # sudo route add -inet default 192.168.1.1

   # Restore DNS servers
   networksetup -setdnsservers "Wi-Fi" 192.168.1.1

   # Flush dns cache
   sudo killall -HUP mDNSResponder;

   sudo kill `ps -ef | grep tun2socks-darwin-amd64-v3 | grep -v grep | awk '{print $2}'`
}

# Gateway mode
# sudo sysctl -n net.inet.ip.forwarding
# sudo sysctl -w net.inet.ip.forwarding=1
# sudo sysctl -w net.inet.ip.forwarding=0

case $1 in
   start)
      tun_create
      ;;
   stop)
      tun_stop
      ;;
   env)
      ;;
   *)
      echo $"Usage $0 {start|stop}"
      exit 1
esac
