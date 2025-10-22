package com.github.cabutchei.wtpcomponent.lsp.model;




public class Mapping {
    private String source;
    private String deployPath;
    private String tag; // wb-resource, dependent-module, etc.

    public Mapping() {
    }

    public Mapping(String tag, String source, String deploy) {
        this.tag = tag;
        this.source = source;
        this.deployPath = deploy;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String s) {
        this.source = s;
    }

    public String getDeployPath() {
        return deployPath;
    }

    public void setDeployPath(String d) {
        this.deployPath = d;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String t) {
        this.tag = t;
    }
}