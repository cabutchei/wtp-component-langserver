package com.github.cabutchei.wtpcomponent.lsp;




import java.io.InputStream;
import java.io.OutputStream;
import org.eclipse.lsp4j.launch.LSPLauncher;




public class ServerMain {
    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        OutputStream out = System.out;
        WtpComponentServer server = new WtpComponentServer();
        var launcher = LSPLauncher.createServerLauncher(server, in, out);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}