package com.github.pangolin.routing.beta.lwip;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

public class LwipTest {
    public static void main(String[] args) {
    }

    public static interface TcpAcceptHandler extends Callback {

        void accept(Pointer _void);

    }
}