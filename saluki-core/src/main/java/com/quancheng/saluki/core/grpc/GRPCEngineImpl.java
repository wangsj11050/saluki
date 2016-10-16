package com.quancheng.saluki.core.grpc;

import java.util.Map;
import java.util.concurrent.Callable;

import com.quancheng.saluki.core.common.SalukiConstants;
import com.quancheng.saluki.core.common.SalukiURL;
import com.quancheng.saluki.core.grpc.client.ProtocolProxyFactory;
import com.quancheng.saluki.core.grpc.interceptor.HeaderClientInterceptor;
import com.quancheng.saluki.core.grpc.interceptor.HeaderServerInterceptor;
import com.quancheng.saluki.core.grpc.server.ProtocolExporter;
import com.quancheng.saluki.core.grpc.server.ProtocolExporterFactory;
import com.quancheng.saluki.core.registry.Registry;
import com.quancheng.saluki.core.registry.RegistryProvider;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.util.RoundRobinLoadBalancerFactory;

public class GRPCEngineImpl implements GRPCEngine {

    private final SalukiURL registryUrl;

    private final Registry  registry;

    public GRPCEngineImpl(SalukiURL registryUrl){
        this.registryUrl = registryUrl;
        this.registry = RegistryProvider.asFactory().newRegistry(registryUrl);
    }

    @Override
    public Object getProxy(SalukiURL refUrl) throws Exception {
        boolean isLocalProcess = refUrl.getParameter(SalukiConstants.GRPC_IN_LOCAL_PROCESS, Boolean.FALSE);
        Callable<Channel> channelCallable = new Callable<Channel>() {

            @Override
            public Channel call() throws Exception {
                Channel channel;
                if (isLocalProcess) {
                    channel = InProcessChannelBuilder.forName(SalukiConstants.GRPC_IN_LOCAL_PROCESS).build();
                } else {
                    channel = ManagedChannelBuilder.forTarget(registryUrl.toJavaURI().toString())//
                                                   .nameResolverFactory(new SalukiNameResolverProvider(refUrl))//
                                                   .loadBalancerFactory(buildLoadBalanceFactory())//
                                                   .usePlaintext(true)//
                                                   .build();//
                }
                return ClientInterceptors.intercept(channel, new HeaderClientInterceptor());
            }
        };
        return ProtocolProxyFactory.getInstance().getProtocolProxy(refUrl, channelCallable).getClient();
    }

    private LoadBalancer.Factory buildLoadBalanceFactory() {
        return RoundRobinLoadBalancerFactory.getInstance();
    }

    @Override
    public SalukiServer getServer(Map<SalukiURL, Object> providerUrls, int port) throws Exception {
        final NettyServerBuilder remoteServer = NettyServerBuilder.forPort(port);
        final InProcessServerBuilder injvmServer = InProcessServerBuilder.forName(SalukiConstants.GRPC_IN_LOCAL_PROCESS);
        for (Map.Entry<SalukiURL, Object> entry : providerUrls.entrySet()) {
            SalukiURL providerUrl = entry.getKey();
            Object protocolImpl = entry.getValue();
            ProtocolExporter protocolExporter = ProtocolExporterFactory.getInstance().getProtocolExporter(providerUrl,
                                                                                                          protocolImpl);
            ServerServiceDefinition serviceDefinition = ServerInterceptors.intercept(protocolExporter.doExport(),
                                                                                     new HeaderServerInterceptor());
            remoteServer.addService(serviceDefinition);
            injvmServer.addService(serviceDefinition);
            registry.register(providerUrl);
        }
        return new SalukiServer(injvmServer.build().start(), remoteServer.build().start());
    }

}
