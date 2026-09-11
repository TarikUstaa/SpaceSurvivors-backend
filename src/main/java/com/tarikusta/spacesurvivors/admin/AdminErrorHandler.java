package com.tarikusta.spacesurvivors.admin;

import com.tarikusta.spacesurvivors.exception.NotFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns a failure in a backoffice page into a page.
 *
 * <p>Without this, every one of them became JSON. {@code ApiExceptionHandler} is a
 * {@code @RestControllerAdvice} registered for the whole application — correct for the game's
 * endpoints, which are read by a program — so a mistyped player id in the address bar answered
 * a ProblemDetail document to somebody looking at a browser. The status was right and the
 * response was useless.</p>
 *
 * <p><b>Scoped and ordered, and both are needed.</b> {@code basePackages} keeps it away from
 * the API, which must keep answering JSON. {@code @Order(HIGHEST_PRECEDENCE)} decides which
 * of the two advices wins for an exception thrown in this package, since both match — without
 * it the winner depends on bean registration order, which is a coin toss that would look
 * settled in testing.</p>
 *
 * <p>What is deliberately <em>not</em> handled here: everything else. An unforeseen exception
 * still goes to {@code ApiExceptionHandler}, which logs it at ERROR. Catching it here to draw
 * a prettier page would mean choosing between duplicating that logging and losing it, and a
 * swallowed 500 is a far worse outcome than an ugly one.</p>
 */
@ControllerAdvice(basePackages = "com.tarikusta.spacesurvivors.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminErrorHandler.class);

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(NotFoundException e, Model model, Authentication authentication) {
        return page(model, authentication, "Not found", e.getMessage());
    }

    /**
     * A path variable that could not be parsed — in practice, something that is not a UUID
     * where a player id belongs.
     *
     * <p>Anyone can produce this by editing the address bar, so it is a 404 rather than a
     * 400: "there is no player with that id" is both true and the thing the reader wants to
     * know, and it does not invite a conversation about what the id should have looked like.</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String badPathValue(MethodArgumentTypeMismatchException e, Model model,
                               Authentication authentication) {
        log.debug("unparseable value for '{}' in a backoffice path", e.getName());
        return page(model, authentication, "Not found",
                "That address does not point at anything.");
    }

    private String page(Model model, Authentication authentication, String title, String detail) {
        model.addAttribute("title", title);
        model.addAttribute("detail", detail);
        // The error page carries the same top bar as every other, so the reader is not
        // stranded — and the name is only known when they are signed in, which for an error
        // inside /admin they always are.
        model.addAttribute("admin", authentication == null ? "" : authentication.getName());
        return "admin/error";
    }
}
