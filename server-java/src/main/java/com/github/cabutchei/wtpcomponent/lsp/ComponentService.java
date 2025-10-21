package com.github.cabutchei.wtpcomponent.lsp;

import java.net.URI;
import java.util.*;
import org.eclipse.lsp4j.*;
import com.github.cabutchei.wtpcomponent.lsp.model.*;
import com.github.cabutchei.wtpcomponent.lsp.xml.ComponentXml;




public class ComponentService {
    public List<Diagnostic> validate(URI uri, String text) {
        try {
            ComponentModel m = ComponentXml.parse(text);
            List<Diagnostic> out = new ArrayList<>();
            // basic checks
            for (Mapping map : m.getMappings()) {
                if (map.getSource() == null || map.getSource().isEmpty())
                    out.add(diag("Missing source", 1, 1));
                if (map.getDeployPath() == null)
                    out.add(diag("Missing deploy-path", 1, 1));
            }
            // TODO: duplicate mapping detection, path existence (via client
            // workspace/requests)
            return out;
        } catch (Exception e) {
            return List.of(diag("Invalid component XML: " + e.getMessage(), 1, 1));
        }
    }

    public List<CodeAction> codeActions(CodeActionParams p) {
        List<CodeAction> list = new ArrayList<>();
        // Add simple quick fix skeletons
        CodeAction add = new CodeAction("Add Mapping…");
        add.setKind(CodeActionKind.QuickFix);
        add.setCommand(new Command("Add Mapping…", "wtp.component.addMapping", List.of(p.getTextDocument().getUri())));
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
}