package com.github.cabutchei.wtpcomponent.lsp;




import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import com.github.cabutchei.wtpcomponent.lsp.model.ComponentModel;
import com.github.cabutchei.wtpcomponent.lsp.wtp.EclipseWorkspaceManager;
import com.github.cabutchei.wtpcomponent.lsp.wtp.StructureEditComponentBackend;




public class WtpComponentServer implements LanguageServer, LanguageClientAware, ComponentEndpoints {

    private LanguageClient client;
    private final EclipseWorkspaceManager workspaceManager = new EclipseWorkspaceManager();
    private final ComponentService service = new ComponentService(new StructureEditComponentBackend(workspaceManager));

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        bootstrapWorkspace(params);

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
        var result = service.validate(uriOf(uri), text);
        publishDiagnostics(uri, result);
    }

    private void publishDiagnostics(String uri, List<Diagnostic> ds) {
        if (client != null) client.publishDiagnostics(new PublishDiagnosticsParams(uri, ds));
    }

    @Override
    public CompletableFuture<ListResult> listMappings(ListParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ComponentModel model = service.loadComponent(uriOf(params.uri), params.text);
                var result = new ListResult();
                result.mappings = model.getMappings().stream().map(m -> {
                    var dto = new MappingDto();
                    dto.source = m.getSource();
                    dto.deployPath = m.getDeployPath();
                    return dto;
                }).toList();
                return result;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<AddResult> addMapping(AddParams params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String updated = service.addMapping(uriOf(params.uri), params.text, params.source, params.deployPath);
                String original = params.text != null ? params.text : updated;
                var edit = new org.eclipse.lsp4j.WorkspaceEdit();
                var change = new org.eclipse.lsp4j.TextEdit(
                    fullDocumentRange(original),
                    updated
                );
                edit.setChanges(java.util.Map.of(params.uri, java.util.List.of(change)));

                var r = new AddResult();
                r.applied = true;
                r.edit = edit;
                client.applyEdit(new org.eclipse.lsp4j.ApplyWorkspaceEditParams(edit));
                return r;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private org.eclipse.lsp4j.Range fullDocumentRange(String text) {
        // naive: count lines, make end large enough
        int lines = (int) text.chars().filter(c -> c == '\n').count() + 1;
        return new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0,0), new org.eclipse.lsp4j.Position(lines+1, 0));
  }

    private void bootstrapWorkspace(InitializeParams params) {
        Set<Path> roots = new LinkedHashSet<>();
        if (params.getWorkspaceFolders() != null) {
            params.getWorkspaceFolders().forEach(folder -> pathFromUri(folder.getUri()).ifPresent(roots::add));
        }
        if (params.getRootUri() != null) {
            pathFromUri(params.getRootUri()).ifPresent(roots::add);
        }
        if (params.getRootPath() != null) {
            roots.add(Path.of(params.getRootPath()));
        }
        Set<IProject> projects = new LinkedHashSet<>();
        roots.forEach(root -> projects.addAll(workspaceManager.importProjects(root)));
        if (!projects.isEmpty()) {
            service.refreshWorkspaceComponents(projects);
        }
    }

    private static Optional<Path> pathFromUri(String uri) {
        if (uri == null || uri.isBlank()) return Optional.empty();
        try {
            URI asUri = URI.create(uri);
            if (!"file".equalsIgnoreCase(asUri.getScheme())) return Optional.empty();
            return Optional.of(Path.of(asUri));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static URI uriOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return URI.create(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
