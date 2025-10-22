package com.github.cabutchei.wtpcomponent.lsp;




// 1) Define the JSON-RPC endpoints
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import java.util.concurrent.CompletableFuture;
import java.util.List;

interface ComponentEndpoints {
    class ListParams {
        public String uri;
        public String text;
    }
    class ListResult { public List<MappingDto> mappings; }

    class AddParams {
        public String uri;
        public String text;
        public String source;
        public String deployPath;
    }
    class AddResult { public boolean applied; public org.eclipse.lsp4j.WorkspaceEdit edit; }

    class MappingDto { public String source; public String deployPath; }

    @JsonRequest("component/listMappings")
    CompletableFuture<ListResult> listMappings(ListParams p);

    @JsonRequest("component/addMapping")
    CompletableFuture<AddResult> addMapping(AddParams p);
}
