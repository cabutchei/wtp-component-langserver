package com.github.cabutchei.wtpcomponent.lsp.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.lsp4j.launch.LSPLauncher;

import com.github.cabutchei.wtpcomponent.lsp.WtpComponentServer;



import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import java.nio.file.Files;

import com.github.cabutchei.wtpcomponent.lsp.wtp.EclipseWorkspaceManager;

/**
 * Eclipse application entry point that boots the language server on stdio.
 */
public final class WtpComponentApplication implements IApplication {

    private static final Logger LOG = Logger.getLogger(WtpComponentApplication.class.getName());

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile Future<?> listening;

    @Override
    public Object start(IApplicationContext context) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;

        LOG.info("Starting WTP Component language server on stdio.");
        WtpComponentServer server = new WtpComponentServer();
        var launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        listening = launcher.startListening();
        LOG.info("Language server is now listening for LSP requests via stdio.");

        // EclipseWorkspaceManager manager = new EclipseWorkspaceManager();
        // manager.importProjects(java.nio.file.Path.of("/Users/qintess/Documents/vs_code/wtp-component-langserver/fake-silce"));
        // manager.getProjects().forEach(p -> LOG.info("Imported project: " + p.getName()));
        // manager.deleteProjects();

	
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
        LOG.info("Language server stop requested; listener cancelled.");
    }
}
