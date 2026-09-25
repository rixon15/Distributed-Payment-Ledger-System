package org.example.grpcauth;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcAuthContextTest {

    private static final AuthenticatedPrincipal PRINCIPAL = new AuthenticatedPrincipal(
            TestTokens.SUBJECT, Set.of(TestTokens.AUDIENCE), Set.of("ledger:read"), "jti-1",
            Instant.parse("2026-09-24T12:00:00Z"), Instant.parse("2026-09-24T12:01:00Z"));

    @Test
    void isEmptyOutsideCall() {
        assertThat(GrpcAuthContext.current()).isEmpty();
        assertThatThrownBy(GrpcAuthContext::requirePrincipal)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    }

    @Test
    void returnsPrincipalAttachedToCurrentContext() throws Exception {
        AuthenticatedPrincipal principal = Context.current()
                .withValue(GrpcAuthContext.PRINCIPAL, PRINCIPAL)
                .call(GrpcAuthContext::requirePrincipal);

        assertThat(principal).isSameAs(PRINCIPAL);
    }

    @Test
    void reachesAnotherThreadOnlyWhenWrapped() throws Exception {
        Context context = Context.current().withValue(GrpcAuthContext.PRINCIPAL, PRINCIPAL);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            boolean plain = context.call(() -> executor.submit(() -> GrpcAuthContext.current().isPresent()).get());
            boolean wrapped = context.call(() -> executor.submit(
                    Context.current().wrap(() -> GrpcAuthContext.current().isPresent())).get());

            assertThat(plain).isFalse();
            assertThat(wrapped).isTrue();
        }
    }

}
