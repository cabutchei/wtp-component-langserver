package com.github.cabutchei.wtpcomponent.lsp;




import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import com.github.cabutchei.wtpcomponent.lsp.ComponentEndpoints.MappingDto;
import com.github.cabutchei.wtpcomponent.lsp.model.ComponentModel;
import com.github.cabutchei.wtpcomponent.lsp.xml.ComponentXml;




public class WtpComponentServer implements LanguageServer, LanguageClientAware, ComponentEndpoints {

    private LanguageClient client;
    private final ComponentService service = new ComponentService();

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        caps.setCodeActionProvider(true);
        caps.setDocumentFormattingProvider(true);
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
    }

    // --- Text Document Service
    private final TextDocumentService text = new TextDocumentService() {
        @Override
        public void didOpen(DidOpenTextDocumentParams p) {
            validate(p.getTextDocument().getUri(), p.getTextDocument().getText());
        }

        @Override
        public void didChange(DidChangeTextDocumentParams p) {
            String text = p.getContentChanges().isEmpty() ? null : p.getContentChanges().get(0).getText();
            if (text != null) validate(p.getTextDocument().getUri(), text);
        }

        @Override
        public void didClose(DidCloseTextDocumentParams p) {
            publishDiagnostics(p.getTextDocument().getUri(), List.of());
        }

        @Override
        public void didSave(DidSaveTextDocumentParams p) {
        }

        @Override
        public java.util.concurrent.CompletableFuture<
            java.util.List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
            // your existing service returns List<CodeAction>
            java.util.List<CodeAction> actions = service.codeActions(params);
            // map to Either<Command, CodeAction>
            java.util.List<Either<Command, CodeAction>> wrapped = new java.util.ArrayList<>(actions.size());
            for (CodeAction a : actions) wrapped.add(Either.forRight(a));
            return java.util.concurrent.CompletableFuture.completedFuture(wrapped);
        }

        @Override
        public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams p) {
            // Optional: pretty-print XML later
            return CompletableFuture.completedFuture(List.of());
        }
    };

    @Override
    public TextDocumentService getTextDocumentService() {
        return text;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {
            // no-op MVP
            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            // no-op MVP
            }
            @Override
            public java.util.concurrent.CompletableFuture<
                Either<java.util.List<? extends SymbolInformation>,
                java.util.List<? extends WorkspaceSymbol>>>
                symbol(WorkspaceSymbolParams params) {
                // MVP: return no results, using the LEFT (old SymbolInformation) branch
                return java.util.concurrent.CompletableFuture.completedFuture(
                    Either.forLeft(java.util.List.of())
                );
            }

            @Override
            public java.util.concurrent.CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
    }

    // --- custom requests (JSON-RPC)
    // binder in lsp4j: we can use launcher.getRemoteProxy on client side; for server we can expose additional endpoints via request manager in future.

    private void validate(String uri, String text) {
        var result = service.validate(URI.create(uri), text);
        publishDiagnostics(uri, result);
    }

    private void publishDiagnostics(String uri, List<Diagnostic> ds) {
        if (client != null) client.publishDiagnostics(new PublishDiagnosticsParams(uri, ds));
    }

    @Override
    public CompletableFuture<ListResult> listMappings(ListParams params) {
        return CompletableFuture.supplyAsync(() -> {
        // var xml = fileLoader.read(p.uri);               // load text
        ComponentModel model;
        try {
            model = ComponentXml.parse(params.text);
            var result = new ListResult();
            result.mappings = model.getMappings().stream().map(m -> {
                var dto = new MappingDto();
                dto.source = m.getSource();
                dto.deployPath = m.getDeployPath();
                return dto;
            }).toList();
            return result;
        } catch (IOException e) {
            // TODO: Handle parse error
            e.printStackTrace();
        }

        return null;
    }
    );
}

    @Override
    public CompletableFuture<AddResult> addMapping(AddParams params) {
        return CompletableFuture.supplyAsync(() -> {
        // var xml = fileLoader.read(params.uri);
        var xml = params.text;
        String updated;
        try {
            updated = ComponentXml.addMapping(xml, params.source, params.deployPath); // you implement this XML edit
            // Build a WorkspaceEdit to replace the whole file (simple MVP).
            // Later, compute a minimal range edit.
            var edit = new org.eclipse.lsp4j.WorkspaceEdit();
            var change = new org.eclipse.lsp4j.TextEdit(
                fullDocumentRange(xml),                 // a helper that returns (0,0)→(∞,∞)
                updated
            );
            edit.setChanges(java.util.Map.of(params.uri, java.util.List.of(change)));
    
            var r = new AddResult();
            r.applied = true;
            r.edit = edit;
            // Server-side: best practice is to ask client to apply the edit:
            client.applyEdit(new org.eclipse.lsp4j.ApplyWorkspaceEditParams(edit));
            return r;
        } catch (IOException e) {
            // TODO: Handle parse error
            e.printStackTrace();
        }
        return null;
    });
}

    private org.eclipse.lsp4j.Range fullDocumentRange(String text) {
        // naive: count lines, make end large enough
        int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
        return new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0,0), new org.eclipse.lsp4j.Position(lines+1, 0));
  }

}