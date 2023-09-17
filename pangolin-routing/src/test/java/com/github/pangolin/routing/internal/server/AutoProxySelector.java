package com.github.pangolin.routing.internal.server;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.net.URL;

public class AutoProxySelector {
    public static void printProxy(final String autoProxyScript, final URL url) throws ScriptException {
        final ScriptEngineManager engineManager = new ScriptEngineManager();
        final ScriptEngine engine = engineManager.getEngineByName("js");
        try {
            engine.eval(autoProxyScript);
            if (engine instanceof Invocable) {
                final Object localObject = ((Invocable) engine).invokeFunction("FindProxyForURL", new Object[]{url.toString(), url.getHost()});
            } else {
                // null
            }
        } catch (final Exception ex) {
            // fallback
        }
    }

}