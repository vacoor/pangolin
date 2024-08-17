# https://github.com/eycorsican/leaf/blob/master/leaf/src/sys.rs
# https://github.com/eycorsican/leaf/blob/master/leaf/src/common/cmd_macos.rs
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/main.js#L702
# https://github.com/mellow-io/mellow/blob/f71f6e54768ded3cfcc46bebb706d46cb8baac08/src/helper/win32/config_route.bat

# In macOS, we need to start tun2socks first so that it will create TUN interface for us.
function tun_create() {
    sudo ./tun2socks-darwin-amd64-v3 -device utun123 -proxy socks5://127.0.0.1:2081 -interface en0
}

# Use ifconfig to bring the TUN interface up and assign addresses for it.
function tun_set_address() {
   sudo ifconfig utun123 inet 198.18.0.1 netmask 255.255.0.0 198.18.0.1 up
   # sudo ifconfig utun123 inet 198.18.0.1 netmask 255.255.0.0 198.18.0.1
}


# Add TUN inteface route.
function tun_setup_route() {
   # Mode 1:
   # Add these specific routes so that tun2socks can handle primary connections.
   # sudo route add -net 1.0.0.0/8 198.18.0.1
   # sudo route add -net 2.0.0.0/7 198.18.0.1
   # sudo route add -net 4.0.0.0/6 198.18.0.1
   # sudo route add -net 8.0.0.0/5 198.18.0.1
   # sudo route add -net 16.0.0.0/4 198.18.0.1
   # sudo route add -net 32.0.0.0/3 198.18.0.1
   # sudo route add -net 64.0.0.0/2 198.18.0.1
   # sudo route add -net 128.0.0.0/1 198.18.0.1
   sudo route add -net 198.18.0.0/15 198.18.0.1

   # Mode 2: Add as default routes
   # sudo route delete -inet default
   # sudo route delete -inet default -ifscope en0
   # sudo route add -inet default 198.18.0.1
   # sudo route add -inet default 192.168.1.1 -ifscope en0
}


# Cleanup TUN interface route.
function tun_cleanup_route() {
   # Mode 1:
   # sudo route delete -net 1.0.0.0/8
   # sudo route delete -net 2.0.0.0/7
   # sudo route delete -net 4.0.0.0/6
   # sudo route delete -net 8.0.0.0/5
   # sudo route delete -net 16.0.0.0/4
   # sudo route delete -net 32.0.0.0/3
   # sudo route delete -net 64.0.0.0/2
   # sudo route delete -net 128.0.0.0/1
   sudo route add -net 198.18.0.0/15 198.18.0.1

   # Mode 2: Restore default routes
   # sudo route delete -inet default
   # sudo route delete -inet default -ifscope en0
   # sudo route add -inet default 192.168.1.1
}

function tun_init() {
   tun_set_address
   tun_setup_route
}

# macOS
# networksetup -listnetworkserviceorder | grep 'Hardware Port'
# networksetup -setdnsservers "Wi-Fi" 127.0.0.1 192.168.1.1 
