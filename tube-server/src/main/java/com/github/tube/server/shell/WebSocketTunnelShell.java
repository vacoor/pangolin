package com.github.tube.server.shell;

import com.github.tube.server.WebSocketTunnelServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

public class WebSocketTunnelShell {
    private boolean breakOnNull;
    private WebSocketTunnelServer server;

    protected final LineReader reader;
    protected final PrintStream output;

    protected WebSocketTunnelShell(final LineReader reader,
                                   final PrintStream output) {
        this.reader = reader;
        this.output = output;
    }

    public boolean next() throws IOException {
        final String line = reader.readLine();
        if (null == line && breakOnNull) {
            return false;
        }

        final String lineToUse = null != line ? line.trim() : "";
        if (lineToUse.length() > 0) {
            this.execute(lineToUse);
        }
        return true;
    }

    protected void execute(final String line) {
        try {
            doExecute(line, output);
        } catch (final Throwable ex) {
        }
    }

    protected void doExecute(final String line, final PrintStream out) {
        // find command and execute
        out.println("-rw-r--r-- 1 YSH8879 1049089  115212 8月  19 17:07  未完成单据.final.xlsx\n" +
                "-rw-r--r-- 1 YSH8879 1049089   24576 9月   3 11:12  现货订单导入模板.clean.xls\n" +
                "-rw-r--r-- 1 YSH8879 1049089   22528 9月   3 12:45  现货订单导入模板.p1.xls\n" +
                "-rw-r--r-- 1 YSH8879 1049089   50176 9月   3 11:09  现货订单导入模板.xls\n" +
                "-rw-r--r-- 1 YSH8879 1049089   86698 12月 23  2019  线路编码Line-codes.jpg\n" +
                "-rw-r--r-- 1 YSH8879 1049089   31232 8月  19 16:48  需维护中间表数据.xls\n" +
                "-rw-r--r-- 1 YSH8879 1049089    9186 8月  19 16:49  需维护中间表数据.xlsx\n" +
                "drwxr-xr-x 1 YSH8879 1049089       0 5月  24 12:35  作业/");
    }
}