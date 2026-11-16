package io.patchradar.release.api;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.patchradar.release.store.Release;
import io.patchradar.release.store.ReleaseStore;
import io.patchradar.release.store.ReleaseStore.Filter;
import io.patchradar.release.store.Tools;

@RestController
public class ReleaseController {
    record Error(String code, String message) {}

    record ToolView(String id, String name, String category, Release.Summary latest) {}

    record Page(List<Release.Summary> items, int page, int size, long total) {}

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    private final ReleaseStore store;
    private final SseHub hub;

    public ReleaseController(ReleaseStore store, SseHub hub) {
        this.store = store;
        this.hub = hub;
    }

    @GetMapping("/api/tools")
    List<ToolView> tools() {
        Map<String, Release> latest = store.latestPerTool().stream()
                .collect(java.util.stream.Collectors.toMap(Release::tool, r -> r));
        return Tools.ALL.stream().map(t -> new ToolView(t.id(), t.name(), t.category(),
                latest.containsKey(t.id()) ? latest.get(t.id()).summary() : null)).toList();
    }

    @GetMapping("/api/releases")
    Page releases(@RequestParam(required = false) List<String> tool,
                  @RequestParam(required = false) String from,
                  @RequestParam(required = false) String to,
                  @RequestParam(required = false) String q,
                  @RequestParam(required = false) Boolean security,
                  @RequestParam(defaultValue = "0") int page,
                  @RequestParam(defaultValue = "20") int size) {
        if (tool != null) {
            for (String t : tool) {
                if (!Tools.exists(t)) {
                    throw new BadRequest("unknown tool: " + t);
                }
            }
        }
        if (page < 0) {
            throw new BadRequest("page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new BadRequest("size must be between 1 and 100");
        }
        if (q != null && q.length() > 100) {
            throw new BadRequest("q must be at most 100 characters");
        }
        Filter f = new Filter(tool, instant("from", from), instant("to", to), q, security);
        return new Page(store.search(f, page, size).stream().map(Release::summary).toList(), page, size,
                store.count(f));
    }

    @GetMapping("/api/releases/{id}")
    ResponseEntity<?> release(@PathVariable long id) {
        return store.find(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new Error("NOT_FOUND", "release not found: " + id)));
    }

    @GetMapping(value = "/api/releases/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return hub.subscribe();
    }

    private static Instant instant(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new BadRequest(name + " must be an ISO-8601 date-time");
        }
    }

    @ExceptionHandler({BadRequest.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Error> badRequest(Exception e) {
        String msg = e instanceof MethodArgumentTypeMismatchException m ? "invalid parameter: " + m.getName()
                : e.getMessage();
        return ResponseEntity.badRequest().body(new Error("BAD_REQUEST", msg));
    }
}
