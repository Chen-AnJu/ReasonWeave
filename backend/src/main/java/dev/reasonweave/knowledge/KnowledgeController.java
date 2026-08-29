package dev.reasonweave.knowledge;

import dev.reasonweave.shared.ApiEnvelope;
import dev.reasonweave.shared.RequestIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "知识与检索", description = "知识源、知识单元与检索快照")
public class KnowledgeController {
    private final KnowledgeService service;

    public KnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @GetMapping("/knowledge/sources")
    @Operation(summary = "列出知识源")
    ApiEnvelope<List<KnowledgeModels.SourceView>> sources(HttpServletRequest request) {
        return ApiEnvelope.of(service.listSources(), RequestIds.current(request));
    }

    @GetMapping("/knowledge/documents")
    @Operation(summary = "列出知识文档")
    ApiEnvelope<List<KnowledgeModels.DocumentView>> documents(
        @RequestParam(name = "source_id", required = false) String sourceId,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.listDocuments(sourceId), RequestIds.current(request));
    }

    @PostMapping("/retrieval/debug")
    @Operation(summary = "执行检索调试")
    ApiEnvelope<KnowledgeModels.RetrievalRunView> debug(
        @Valid @RequestBody KnowledgeModels.RetrievalRequest body,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.debug(body), RequestIds.current(request));
    }

    @GetMapping("/retrieval/runs/{id}")
    @Operation(summary = "读取检索运行快照")
    ApiEnvelope<KnowledgeModels.RetrievalRunView> run(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.getRetrievalRun(id), RequestIds.current(request));
    }

    @GetMapping("/knowledge/sources/{id}")
    @Operation(summary = "读取知识源详情")
    ApiEnvelope<KnowledgeModels.SourceDetailView> source(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.getSourceDetail(id), RequestIds.current(request));
    }

    @GetMapping("/knowledge/sources/{id}/units")
    @Operation(summary = "分页读取知识源的知识单元")
    ApiEnvelope<KnowledgeModels.UnitPageView> units(
        @PathVariable String id,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "50") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.listUnits(id, cursor, limit), RequestIds.current(request));
    }

    @GetMapping("/knowledge/units/{id}")
    @Operation(summary = "读取知识单元详情与使用记录")
    ApiEnvelope<KnowledgeModels.UnitDetailView> unit(
        @PathVariable String id,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(service.getUnitDetail(id), RequestIds.current(request));
    }

    @GetMapping("/knowledge/units/{id}/citation-usages")
    @Operation(summary = "分页读取知识单元引用记录")
    ApiEnvelope<KnowledgeModels.CitationUsagePageView> citationUsages(
        @PathVariable String id,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            service.listCitationUsages(id, cursor, limit),
            RequestIds.current(request)
        );
    }

    @GetMapping("/knowledge/units/{id}/retrieval-usages")
    @Operation(summary = "分页读取知识单元检索使用记录")
    ApiEnvelope<KnowledgeModels.RetrievalUsagePageView> retrievalUsages(
        @PathVariable String id,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") int limit,
        HttpServletRequest request
    ) {
        return ApiEnvelope.of(
            service.listRetrievalUsages(id, cursor, limit),
            RequestIds.current(request)
        );
    }
}
