package com.github.cabutchei.wtpcomponent.lsp.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.lsp4j.launch.LSPLauncher;

import com.github.cabutchei.wtpcomponent.lsp.WtpComponentServer;

/**
 * Eclipse application entry point that boots the language server on stdio.
 */
public final class WtpComponentApplication implements IApplication {

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile Future<?> listening;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;

        WtpComponentServer server = new WtpComponentServer();
        var launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        listening = launcher.startListening();
        try {
            listening.get();
        } catch (Exception ex) {
            if (!stopping.get()) throw ex;
        }
        return IApplication.EXIT_OK;
    }

    @Override
    public void stop() {
        stopping.set(true);
        Future<?> future = listening;
        if (future != null) {
            future.cancel(true);
        }
    }
}
