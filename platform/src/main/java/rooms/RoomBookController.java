package rooms;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class RoomBookController {
    private final BookingStore store;
    private final Accounts accounts;

    RoomBookController(BookingStore store, Accounts accounts) {
        this.store = store;
        this.accounts = accounts;
    }

    @RequestMapping("/{*path}")
    ResponseEntity<String> dispatch(HttpServletRequest request) throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Server.HttpReply reply = Server.dispatch(store, accounts, path, request.getMethod(), request.getQueryString(),
            request.getHeader(HttpHeaders.AUTHORIZATION), request.getContentType(), request.getInputStream(),
            request.getRemoteAddr());

        var response = ResponseEntity.status(reply.status)
            .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
            .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (reply.allow != null) {
            response.header(HttpHeaders.ALLOW, reply.allow);
        }
        return response.body(reply.body);
    }
}
