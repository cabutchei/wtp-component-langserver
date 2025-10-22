package com.github.cabutchei.wtpcomponent.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import com.github.cabutchei.wtpcomponent.lsp.model.ComponentModel;
import com.github.cabutchei.wtpcomponent.lsp.model.Mapping;
import com.github.cabutchei.wtpcomponent.lsp.wtp.StructureEditComponentBackend;
import com.github.cabutchei.wtpcomponent.lsp.xml.ComponentXml;
import org.eclipse.core.resources.IProject;

public class ComponentService {

    private final StructureEditComponentBackend backend;

    public ComponentService(StructureEditComponentBackend backend) {
        this.backend = backend;
    }

    void refreshWorkspaceComponents(Collection<IProject> projects) {
        if (backend != null) {
            backend.refreshComponents(projects);
        }
    }

    public List<Diagnostic> validate(URI uri, String text) {
        try {
            ComponentModel model = loadComponent(uri, text);
            List<Diagnostic> out = new ArrayList<>();
            for (Mapping map : model.getMappings()) {
                if (map.getSource() == null || map.getSource().isEmpty())
                    out.add(diag("Missing source", 1, 1));
                if (map.getDeployPath() == null)
                    out.add(diag("Missing deploy-path", 1, 1));
            }
            return out;
        } catch (Exception e) {
            return List.of(diag("Invalid component XML: " + e.getMessage(), 1, 1));
        }
    }

    public ComponentModel loadComponent(URI uri, String fallbackText) throws IOException {
        if (backend != null) {
            Optional<ComponentModel> model = backend.readComponent(uri);
            if (model.isPresent()) {
                return model.get();
            }
        }

        String xml = supplyText(uri, fallbackText);
        if (xml == null) return new ComponentModel();
        return ComponentXml.parse(xml);
    }

    public String addMapping(URI uri, String currentText, String source, String deployPath) throws IOException {
        if (backend != null) {
            Optional<String> updated = backend.addMapping(uri, source, deployPath);
            if (updated.isPresent()) {
                return updated.get();
            }
        }
        String xml = supplyText(uri, currentText);
        if (xml == null) throw new IOException("No component XML available for " + uri);
        return ComponentXml.addMapping(xml, source, deployPath);
    }

    public List<CodeAction> codeActions(CodeActionParams params) {
        List<CodeAction> list = new ArrayList<>();
        CodeAction add = new CodeAction("Add Mapping…");
        add.setKind(CodeActionKind.QuickFix);
        add.setCommand(new Command("Add Mapping…", "wtp.component.addMapping",
            List.of(params.getTextDocument().getUri())));
        list.add(add);
        return list;
    }

    private static Diagnostic diag(String msg, int line, int col) {
        Diagnostic d = new Diagnostic();
        d.setMessage(msg);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setRange(new Range(new Position(line, col), new Position(line, col + 1)));
        return d;
    }

    private static String supplyText(URI uri, String fallback) throws IOException {
        if (fallback != null) return fallback;
        if (uri == null) return null;
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        Path path;
        try {
            path = Path.of(uri);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (!Files.exists(path)) return null;
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
