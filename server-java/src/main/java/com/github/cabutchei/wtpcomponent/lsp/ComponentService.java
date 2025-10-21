package com.github.cabutchei.wtpcomponent.lsp;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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

    public List<CompletionItem> completions(CompletionParams params, String text) {
        if (text == null) {
            return List.of();
        }
        int offset = TextDocumentUtils.offsetAt(text, params.getPosition());
        CompletionState state = CompletionState.analyze(text, offset);
        if (state.kind == CompletionKind.NONE) {
            return List.of();
        }
        return switch (state.kind) {
            case TAG_NAME -> buildElementCompletions(state, text);
            case CLOSE_TAG -> buildClosingTagCompletion(state, text);
            case ATTRIBUTE_NAME -> buildAttributeCompletions(state, text);
            case ATTRIBUTE_VALUE -> buildAttributeValueCompletions(state, text);
            case NONE -> List.of();
        };
    }

    private static Diagnostic diag(String msg, int line, int col) {
        Diagnostic d = new Diagnostic();
        d.setMessage(msg);
        d.setSeverity(DiagnosticSeverity.Warning);
        d.setRange(new Range(new Position(line, col), new Position(line, col + 1)));
        return d;
    }

    private List<CompletionItem> buildElementCompletions(CompletionState state, String text) {
        List<ElementSpec> candidates = elementCandidatesFor(state.parentElement);
        List<CompletionItem> out = new ArrayList<>();
        for (ElementSpec spec : candidates) {
            if (!state.prefix.isEmpty() && !spec.name.startsWith(state.prefix)) continue;
            CompletionItem item = new CompletionItem(spec.name);
            item.setKind(CompletionItemKind.Struct);
            item.setDetail(spec.detail);
            Range range = new Range(
                TextDocumentUtils.positionAt(text, state.replaceStartOffset),
                TextDocumentUtils.positionAt(text, state.replaceEndOffset)
            );
            item.setTextEdit(Either.forLeft(new TextEdit(range, spec.snippet)));
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setSortText("1_" + spec.name);
            out.add(item);
        }
        return out;
    }

    private List<CompletionItem> buildClosingTagCompletion(CompletionState state, String text) {
        if (state.elementName == null || state.elementName.isEmpty()) return List.of();
        String suggestion = state.elementName + ">";
        if (!state.prefix.isEmpty() && !state.elementName.startsWith(state.prefix)) {
            return List.of();
        }
        CompletionItem item = new CompletionItem(state.elementName);
        item.setKind(CompletionItemKind.Keyword);
        item.setDetail("Close <" + state.elementName + ">");
        Range range = new Range(
            TextDocumentUtils.positionAt(text, state.replaceStartOffset),
            TextDocumentUtils.positionAt(text, state.replaceEndOffset)
        );
        item.setTextEdit(Either.forLeft(new TextEdit(range, suggestion)));
        item.setSortText("0_" + state.elementName);
        return List.of(item);
    }

    private List<CompletionItem> buildAttributeCompletions(CompletionState state, String text) {
        if (state.elementName == null) return List.of();
        ElementSpec spec = ELEMENTS.get(state.elementName);
        if (spec == null) return List.of();
        List<CompletionItem> out = new ArrayList<>();
        for (AttributeSpec attr : spec.attributes) {
            if (state.presentAttributes.contains(attr.name)) continue;
            if (!state.prefix.isEmpty() && !attr.name.startsWith(state.prefix)) continue;
            CompletionItem item = new CompletionItem(attr.name);
            item.setKind(CompletionItemKind.Property);
            item.setDetail(attr.detail);
            if (attr.documentation != null) {
            }
            Range range = new Range(
                TextDocumentUtils.positionAt(text, state.replaceStartOffset),
                TextDocumentUtils.positionAt(text, state.replaceEndOffset)
            );
            String insert = attr.name + "=\"${1:" + attr.defaultValue + "}\"";
            item.setTextEdit(Either.forLeft(new TextEdit(range, insert)));
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setSortText("1_" + attr.name);
            out.add(item);
        }
        return out;
    }

    private List<CompletionItem> buildAttributeValueCompletions(CompletionState state, String text) {
        if (state.elementName == null) return List.of();
        ElementSpec spec = ELEMENTS.get(state.elementName);
        if (spec == null) return List.of();
        AttributeSpec attr = spec.attributeByName(state.attributeName);
        if (attr == null) return List.of();
        List<CompletionItem> out = new ArrayList<>();
        for (int i = 0; i < attr.suggestions.size(); i++) {
            String suggestion = attr.suggestions.get(i);
            if (!state.prefix.isEmpty() && !suggestion.startsWith(state.prefix)) continue;
            CompletionItem item = new CompletionItem(suggestion);
            item.setKind(CompletionItemKind.Value);
            item.setDetail(attr.detail);
            Range range = new Range(
                TextDocumentUtils.positionAt(text, state.replaceStartOffset),
                TextDocumentUtils.positionAt(text, state.replaceEndOffset)
            );
            item.setTextEdit(Either.forLeft(new TextEdit(range, suggestion)));
            item.setSortText(String.format("0_%02d_%s", i, suggestion));
            out.add(item);
        }
        if (out.isEmpty() && attr.defaultValue != null && !attr.defaultValue.isEmpty()) {
            CompletionItem item = new CompletionItem(attr.defaultValue);
            item.setKind(CompletionItemKind.Value);
            item.setDetail(attr.detail);
            Range range = new Range(
                TextDocumentUtils.positionAt(text, state.replaceStartOffset),
                TextDocumentUtils.positionAt(text, state.replaceEndOffset)
            );
            item.setTextEdit(Either.forLeft(new TextEdit(range, attr.defaultValue)));
            out.add(item);
        }
        return out;
    }

    private List<ElementSpec> elementCandidatesFor(String parent) {
        ElementSpec spec = parent == null ? null : ELEMENTS.get(parent);
        List<String> childNames = spec == null ? ROOT_ELEMENTS : spec.children;
        if (childNames == null || childNames.isEmpty()) {
            return List.of();
        }
        List<ElementSpec> out = new ArrayList<>();
        for (String name : childNames) {
            ElementSpec child = ELEMENTS.get(name);
            if (child != null) out.add(child);
        }
        return out;
    }

    private enum CompletionKind {
        TAG_NAME, CLOSE_TAG, ATTRIBUTE_NAME, ATTRIBUTE_VALUE, NONE
    }

    private static final class CompletionState {
        final CompletionKind kind;
        final String elementName;
        final String attributeName;
        final String parentElement;
        final String prefix;
        final int replaceStartOffset;
        final int replaceEndOffset;
        final Set<String> presentAttributes;

        private CompletionState(
            CompletionKind kind,
            String elementName,
            String attributeName,
            String parentElement,
            String prefix,
            int replaceStartOffset,
            int replaceEndOffset,
            Set<String> presentAttributes
        ) {
            this.kind = kind;
            this.elementName = elementName;
            this.attributeName = attributeName;
            this.parentElement = parentElement;
            this.prefix = prefix;
            this.replaceStartOffset = replaceStartOffset;
            this.replaceEndOffset = replaceEndOffset;
            this.presentAttributes = presentAttributes == null ? Set.of() : presentAttributes;
        }

        static CompletionState analyze(String text, int offset) {
            if (text == null || offset < 0 || offset > text.length()) {
                return empty();
            }
            int tagStart = text.lastIndexOf('<', Math.max(0, offset - 1));
            if (tagStart < 0) {
                return empty();
            }

            int previousGt = text.lastIndexOf('>', offset - 1);
            if (previousGt > tagStart) {
                return empty();
            }

            String inside = text.substring(tagStart + 1, offset);
            if (inside.startsWith("?") || inside.startsWith("!")) {
                return empty();
            }

            Deque<String> stack = openElements(text, tagStart);
            String parent = stack.isEmpty() ? null : stack.peek();

            if (inside.startsWith("/")) {
                String partial = readNameSuffix(inside.substring(1));
                String expected = stack.isEmpty() ? "" : stack.pop();
                return new CompletionState(
                    CompletionKind.CLOSE_TAG,
                    expected,
                    null,
                    parent,
                    partial,
                    tagStart + 2,
                    offset,
                    Set.of()
                );
            }

            int idx = 0;
            while (idx < inside.length() && Character.isWhitespace(inside.charAt(idx))) idx++;
            int nameStart = idx;
            while (idx < inside.length() && isNameChar(inside.charAt(idx))) idx++;
            int nameEnd = idx;
            String nameFragment = inside.substring(nameStart, nameEnd);

            if (nameFragment.isEmpty() && nameStart == inside.length()) {
                return new CompletionState(
                    CompletionKind.TAG_NAME,
                    null,
                    null,
                    parent,
                    "",
                    tagStart + 1,
                    offset,
                    Set.of()
                );
            }

            if (idx == inside.length()) {
                return new CompletionState(
                    CompletionKind.TAG_NAME,
                    null,
                    null,
                    parent,
                    nameFragment,
                    tagStart + 1 + nameStart,
                    tagStart + 1 + nameEnd,
                    Set.of()
                );
            }

            String elementName = nameFragment;
            String afterName = inside.substring(nameEnd);
            Set<String> presentAttrs = extractAttributeNames(afterName);

            AttributeContext attrContext = AttributeContext.fromInside(
                inside,
                nameEnd,
                tagStart,
                offset
            );

            if (attrContext.kind == CompletionKind.ATTRIBUTE_VALUE) {
                return new CompletionState(
                    CompletionKind.ATTRIBUTE_VALUE,
                    elementName,
                    attrContext.attributeName,
                    parent,
                    attrContext.prefix,
                    attrContext.replaceStart,
                    attrContext.replaceEnd,
                    presentAttrs
                );
            }

            return new CompletionState(
                CompletionKind.ATTRIBUTE_NAME,
                elementName,
                null,
                parent,
                attrContext.prefix,
                attrContext.replaceStart,
                attrContext.replaceEnd,
                presentAttrs
            );
        }

        private static CompletionState empty() {
            return new CompletionState(CompletionKind.NONE, null, null, null, "", 0, 0, Set.of());
        }
    }

    private static class AttributeContext {
        final CompletionKind kind;
        final String attributeName;
        final String prefix;
        final int replaceStart;
        final int replaceEnd;

        private AttributeContext(CompletionKind kind, String attributeName, String prefix, int replaceStart, int replaceEnd) {
            this.kind = kind;
            this.attributeName = attributeName;
            this.prefix = prefix;
            this.replaceStart = replaceStart;
            this.replaceEnd = replaceEnd;
        }

        static AttributeContext fromInside(String inside, int nameEnd, int tagStart, int absoluteOffset) {
            boolean insideQuotes = isInsideQuotes(inside, nameEnd);
            if (insideQuotes) {
                int eqIndex = lastIndexOfOutsideQuotes(inside, nameEnd, '=');
                if (eqIndex >= 0) {
                    int nameEndIdx = eqIndex - 1;
                    while (nameEndIdx > nameEnd && Character.isWhitespace(inside.charAt(nameEndIdx))) nameEndIdx--;
                    int nameStartIdx = nameEndIdx;
                    while (nameStartIdx > nameEnd && isNameChar(inside.charAt(nameStartIdx))) nameStartIdx--;
                    String attrName = inside.substring(nameStartIdx + 1, nameEndIdx + 1);

                    int quoteStart = eqIndex + 1;
                    while (quoteStart < inside.length() && inside.charAt(quoteStart) != '"' && inside.charAt(quoteStart) != '\'') {
                        quoteStart++;
                    }
                    if (quoteStart < inside.length()) {
                        quoteStart++;
                        String prefix = inside.substring(quoteStart);
                        int replaceStart = tagStart + 1 + quoteStart;
                        return new AttributeContext(CompletionKind.ATTRIBUTE_VALUE, attrName, prefix, replaceStart, absoluteOffset);
                    }
                }
            }

            String attributesPart = inside.substring(nameEnd);
            int idx = attributesPart.length() - 1;
            while (idx >= 0 && Character.isWhitespace(attributesPart.charAt(idx))) idx--;
            if (idx < 0) {
                return new AttributeContext(CompletionKind.ATTRIBUTE_NAME, null, "", absoluteOffset, absoluteOffset);
            }
            if (attributesPart.charAt(idx) == '/') {
                return new AttributeContext(CompletionKind.ATTRIBUTE_NAME, null, "", absoluteOffset, absoluteOffset);
            }
            int end = idx + 1;
            while (idx >= 0 && isNameChar(attributesPart.charAt(idx))) idx--;
            String partial = attributesPart.substring(idx + 1, end);
            int before = idx;
            while (before >= 0 && Character.isWhitespace(attributesPart.charAt(before))) before--;
            if (before >= 0 && attributesPart.charAt(before) == '=') {
                return new AttributeContext(CompletionKind.ATTRIBUTE_VALUE, partial, "", absoluteOffset, absoluteOffset);
            }
            int replaceStart = absoluteOffset - partial.length();
            return new AttributeContext(CompletionKind.ATTRIBUTE_NAME, null, partial, replaceStart, absoluteOffset);
        }
    }

    private static Set<String> extractAttributeNames(String fragment) {
        if (fragment == null || fragment.isEmpty()) return Set.of();
        Pattern p = Pattern.compile("([A-Za-z0-9:_-]+)\\s*=");
        Matcher m = p.matcher(fragment);
        Set<String> out = new HashSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static boolean isInsideQuotes(String inside, int nameEnd) {
        boolean insideQuotes = false;
        char quoteChar = 0;
        for (int i = nameEnd; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '"' || c == '\'') {
                if (!insideQuotes) {
                    insideQuotes = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    insideQuotes = false;
                }
            }
        }
        return insideQuotes;
    }

    private static int lastIndexOfOutsideQuotes(String text, int from, char target) {
        boolean insideQuotes = false;
        char quoteChar = 0;
        for (int i = text.length() - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                if (!insideQuotes) {
                    insideQuotes = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    insideQuotes = false;
                    quoteChar = 0;
                }
                continue;
            }
            if (!insideQuotes && c == target) {
                return i;
            }
        }
        return -1;
    }

    private static String readNameSuffix(String s) {
        int idx = s.length() - 1;
        while (idx >= 0 && !isNameChar(s.charAt(idx))) idx--;
        int end = idx + 1;
        while (idx >= 0 && isNameChar(s.charAt(idx))) idx--;
        return s.substring(idx + 1, end);
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':';
    }

    private static Deque<String> openElements(String text, int limit) {
        Deque<String> stack = new ArrayDeque<>();
        int index = 0;
        while (index < limit) {
            int lt = text.indexOf('<', index);
            if (lt < 0 || lt >= limit) break;
            int gt = text.indexOf('>', lt);
            if (gt < 0 || gt >= limit) break;
            if (lt + 1 >= gt) {
                index = gt + 1;
                continue;
            }
            char next = text.charAt(lt + 1);
            if (next == '!' || next == '?') {
                index = gt + 1;
                continue;
            }
            boolean closing = next == '/';
            int nameStart = closing ? lt + 2 : lt + 1;
            int pos = nameStart;
            while (pos < gt && isNameChar(text.charAt(pos))) pos++;
            if (pos == nameStart) {
                index = gt + 1;
                continue;
            }
            String name = text.substring(nameStart, pos);
            boolean selfClosing = !closing && text.charAt(gt - 1) == '/';
            if (closing) {
                while (!stack.isEmpty()) {
                    String top = stack.pop();
                    if (top.equals(name)) break;
                }
            } else {
                if (!selfClosing) stack.push(name);
            }
            index = gt + 1;
        }
        return stack;
    }

    private static final class ElementSpec {
        final String name;
        final String detail;
        final String documentation;
        final boolean selfClosing;
        final List<String> children;
        final List<AttributeSpec> attributes;
        final String snippet;

        ElementSpec(String name, String detail, String documentation, boolean selfClosing, List<String> children, List<AttributeSpec> attributes, String snippet) {
            this.name = name;
            this.detail = detail;
            this.documentation = documentation;
            this.selfClosing = selfClosing;
            this.children = children;
            this.attributes = attributes;
            this.snippet = snippet;
        }

        AttributeSpec attributeByName(String name) {
            for (AttributeSpec attr : attributes) {
                if (attr.name.equals(name)) return attr;
            }
            return null;
        }
    }

    private static final class AttributeSpec {
        final String name;
        final String detail;
        final String documentation;
        final List<String> suggestions;
        final String defaultValue;

        AttributeSpec(String name, String detail, String documentation, List<String> suggestions, String defaultValue) {
            this.name = name;
            this.detail = detail;
            this.documentation = documentation;
            this.suggestions = suggestions;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
        }
    }

    private static AttributeSpec attr(String name, String detail, String documentation, List<String> suggestions, String defaultValue) {
        return new AttributeSpec(name, detail, documentation, suggestions, defaultValue);
    }

    private static ElementSpec element(
        String name,
        String detail,
        String documentation,
        boolean selfClosing,
        List<String> children,
        List<AttributeSpec> attributes,
        String snippet
    ) {
        return new ElementSpec(name, detail, documentation, selfClosing, children, attributes, snippet);
    }

    private static final AttributeSpec PROJECT_ID = attr(
        "id",
        "Identifier for this deployment assembly",
        "Identifier assigned by Eclipse WTP for the collection of modules.",
        List.of("component.core"),
        "component.core"
    );

    private static final AttributeSpec MODULE_DEPLOY_NAME = attr(
        "deploy-name",
        "Deployment name used at runtime",
        "Name of the module when deployed to the application server.",
        List.of("MyModule"),
        "MyModule"
    );

    private static final AttributeSpec MODULE_TYPE_ID = attr(
        "module-type-id",
        "Module type identifier",
        "Typical values include jst.web for web modules or jst.utility for utility projects.",
        List.of("jst.web", "jst.utility", "jst.ejb"),
        "jst.web"
    );

    private static final AttributeSpec RESOURCE_SOURCE = attr(
        "source-path",
        "Folder to include in the module",
        "Workspace path, typically starting with /. Examples: /src/main/webapp.",
        List.of("/src/main/webapp", "/src/main/java", "/src/main/resources"),
        "/src/main/webapp"
    );

    private static final AttributeSpec RESOURCE_DEPLOY = attr(
        "deploy-path",
        "Published location inside the module",
        "Destination inside the runtime module or archive.",
        List.of("/", "/WEB-INF/classes", "/WEB-INF/lib"),
        "/"
    );

    private static final AttributeSpec DEPENDENT_HANDLE = attr(
        "handle",
        "Handle to the dependent module",
        "Use module:/Project/Module style handles exposed by Eclipse.",
        List.of("module:/MyProject/MyDependentModule"),
        "module:/MyProject/MyDependentModule"
    );

    private static final AttributeSpec DEPENDENT_ARCHIVE = attr(
        "archiveName",
        "Custom archive name",
        "Override the dependent module archive name when deployed.",
        List.of("dependency.jar"),
        "dependency.jar"
    );

    private static final AttributeSpec DEPENDENT_DEPLOY = attr(
        "deploy-path",
        "Where to deploy the dependency",
        "Runtime location for the dependent module contribution.",
        List.of("/WEB-INF/lib", "/"),
        "/WEB-INF/lib"
    );

    private static final AttributeSpec DEPENDENT_TYPE = attr(
        "dependency-type",
        "Classify the dependency",
        "Optional dependency classification used by WTP.",
        List.of("uses", "consumes", "exports"),
        "uses"
    );

    private static final List<String> ROOT_ELEMENTS = List.of("project-modules", "wb-module");

    private static final Map<String, ElementSpec> ELEMENTS = Map.ofEntries(
        Map.entry("project-modules", element(
            "project-modules",
            "WTP project module container",
            "Root container holding one or more <wb-module> entries.",
            false,
            List.of("wb-module"),
            List.of(PROJECT_ID),
            "<project-modules id=\"${1:component.core}\">\n  $0\n</project-modules>"
        )),
        Map.entry("wb-module", element(
            "wb-module",
            "A deployable module",
            "Defines how the project contents map into the deployed module.",
            false,
            List.of("module-type", "wb-resource", "dependent-module"),
            List.of(MODULE_DEPLOY_NAME),
            "<wb-module deploy-name=\"${1:MyModule}\">\n  $0\n</wb-module>"
        )),
        Map.entry("module-type", element(
            "module-type",
            "Module type metadata",
            "Declares the module type identifier recognized by WTP.",
            true,
            List.of(),
            List.of(MODULE_TYPE_ID),
            "<module-type module-type-id=\"${1:jst.web}\" />"
        )),
        Map.entry("wb-resource", element(
            "wb-resource",
            "Resource mapping",
            "Map source folders to runtime deployment paths.",
            true,
            List.of(),
            List.of(RESOURCE_SOURCE, RESOURCE_DEPLOY),
            "<wb-resource source-path=\"${1:/src/main/webapp}\" deploy-path=\"${2:/}\" />"
        )),
        Map.entry("dependent-module", element(
            "dependent-module",
            "Dependent module reference",
            "Include another module contribution in this assembly.",
            true,
            List.of(),
            List.of(DEPENDENT_HANDLE, DEPENDENT_ARCHIVE, DEPENDENT_DEPLOY, DEPENDENT_TYPE),
            "<dependent-module handle=\"${1:module:/MyProject/MyDependentModule}\" archiveName=\"${2:dependency.jar}\" deploy-path=\"${3:/WEB-INF/lib}\" dependency-type=\"${4:uses}\" />"
        ))
    );
}
