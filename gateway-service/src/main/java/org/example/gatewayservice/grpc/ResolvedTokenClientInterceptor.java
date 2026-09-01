package org.example.gatewayservice.grpc;

import io.grpc.*;
import org.example.gatewayservice.auth.ResolvedTokenContext;
import org.springframework.stereotype.Component;

@Component
public class ResolvedTokenClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> methodDescriptor,
                                                               CallOptions callOptions, Channel channel) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(channel.newCall(methodDescriptor, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String token = ResolvedTokenContext.get();
                if (token != null) {
                    headers.put(AUTHORIZATION_KEY, "Bearer " + token);
                }
                super.start(responseListener, headers);
            }
        };
    }

}