package com.github.cabutchei.wtpcomponent.lsp.xml;




import java.io.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import com.github.cabutchei.wtpcomponent.lsp.model.*;




public class ComponentXml {

    public static ComponentModel parse(String xml) throws IOException {
        Document d = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        ComponentModel m = new ComponentModel();
        // supports both .component and org.eclipse.wst.common.component structures
        for (Element e : d.select("wb-resource, dependent-module")) {
            String tag = e.tagName();
            String source = firstAttr(e, "source-path", "sourcePath", "deploy-source");
            String deploy = firstAttr(e, "deploy-path", "deployPath", "target");
            if (source != null || deploy != null)
                m.getMappings().add(new Mapping(tag, source, deploy));
        }
        return m;
    }

    private static String firstAttr(Element e, String... names) {
        for (String n : names) {
            String v = e.attr(n);
            if (v != null && !v.isEmpty())
                return v;
        }
        return null;
    }

    /**
     * Find a mapping in the XML whose source and deployPath match (exact string match).
     * Returns null if not found.
     */
    public static Mapping getMapping(String xml, String source, String deployPath) throws IOException {
        ComponentModel m = parse(xml);
        for (Mapping map : m.getMappings()) {
            boolean srcEq = eq(map.getSource(), source);
            boolean depEq = eq(map.getDeployPath(), deployPath);
            if (srcEq && depEq) {
                return map;
            }
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    /**
     * Idempotently add a <wb-resource> mapping under <wb-module>.
     * If the exact mapping already exists, returns the original xml.
     * Otherwise, appends a new element and returns the updated xml.
     *
     * @param xml         the original component XML
     * @param sourcePath  e.g. "/src" or "/src/main/webapp"
     * @param deployPath  e.g. "/WEB-INF/classes" or "/"
     * @return updated XML string (pretty-printed)
     */
    public static String addMapping(String xml, String sourcePath, String deployPath) throws IOException {
        // Normalize minimal invariants; do not enforce leading "/" strictly in case your model wants raw values
        String src = sourcePath == null ? "" : sourcePath;
        String dep = deployPath == null ? "" : deployPath;

        Document d = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser());
        Element wb = findOrCreateWbModule(d);

        // Already present?
        if (hasWbResource(wb, src, dep)) {
            return pretty(d);
        }

        // Append <wb-resource>
        Element res = d.createElement("wb-resource");
        res.attr("source-path", src);
        res.attr("deploy-path", dep);
        wb.appendChild(res);

        return pretty(d);
    }

    // ---------- helpers ----------

    /** Locate <wb-module> regardless of root shape; create one if missing. */
    private static Element findOrCreateWbModule(Document d) {
        Element wb = d.selectFirst("wb-module");
        if (wb != null) return wb;

        // WTP common structure: project-modules > wb-module
        Element pm = d.selectFirst("project-modules");
        if (pm != null) {
            wb = pm.selectFirst("wb-module");
            if (wb != null) return wb;
            // create one if project-modules exists but has no wb-module
            wb = d.createElement("wb-module");
            pm.appendChild(wb);
            return wb;
        }

        // Fallback: create wb-module under document root
        Element root = d.children().isEmpty() ? d : d.children().first();
        if (root == null) {
            // If the document is empty, create a top-level wb-module
            wb = d.createElement("wb-module");
            d.appendChild(wb);
            return wb;
        }
        wb = d.createElement("wb-module");
        root.appendChild(wb);
        return wb;
    }

    /** Check if an identical <wb-resource> exists. */
    private static boolean hasWbResource(Element wbModule, String sourcePath, String deployPath) {
        for (Element e : wbModule.select("> wb-resource")) {
            String src = e.attr("source-path");
            String dep = e.attr("deploy-path");
            if (eq(src, sourcePath) && eq(dep, deployPath)) {
                return true;
            }
        }
        return false;
    }

    /** Pretty-print the XML as a string, preserving XML syntax. */
    private static String pretty(Document d) {
        Document.OutputSettings os = new Document.OutputSettings();
        os.prettyPrint(true).indentAmount(2).syntax(Document.OutputSettings.Syntax.xml);
        d.outputSettings(os);
        return d.outerHtml();
    }

}