package com.neo.dashboard.asr.riva.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.71.0)",
    comments = "Source: nvidia/riva/riva_asr.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RivaSpeechRecognitionGrpc {

  private RivaSpeechRecognitionGrpc() {}

  public static final java.lang.String SERVICE_NAME = "nvidia.riva.asr.RivaSpeechRecognition";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.RecognizeRequest,
      com.neo.dashboard.asr.riva.proto.RecognizeResponse> getRecognizeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Recognize",
      requestType = com.neo.dashboard.asr.riva.proto.RecognizeRequest.class,
      responseType = com.neo.dashboard.asr.riva.proto.RecognizeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.RecognizeRequest,
      com.neo.dashboard.asr.riva.proto.RecognizeResponse> getRecognizeMethod() {
    io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.RecognizeRequest, com.neo.dashboard.asr.riva.proto.RecognizeResponse> getRecognizeMethod;
    if ((getRecognizeMethod = RivaSpeechRecognitionGrpc.getRecognizeMethod) == null) {
      synchronized (RivaSpeechRecognitionGrpc.class) {
        if ((getRecognizeMethod = RivaSpeechRecognitionGrpc.getRecognizeMethod) == null) {
          RivaSpeechRecognitionGrpc.getRecognizeMethod = getRecognizeMethod =
              io.grpc.MethodDescriptor.<com.neo.dashboard.asr.riva.proto.RecognizeRequest, com.neo.dashboard.asr.riva.proto.RecognizeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Recognize"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.neo.dashboard.asr.riva.proto.RecognizeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.neo.dashboard.asr.riva.proto.RecognizeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RivaSpeechRecognitionMethodDescriptorSupplier("Recognize"))
              .build();
        }
      }
    }
    return getRecognizeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest,
      com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse> getStreamingRecognizeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "StreamingRecognize",
      requestType = com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest.class,
      responseType = com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest,
      com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse> getStreamingRecognizeMethod() {
    io.grpc.MethodDescriptor<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest, com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse> getStreamingRecognizeMethod;
    if ((getStreamingRecognizeMethod = RivaSpeechRecognitionGrpc.getStreamingRecognizeMethod) == null) {
      synchronized (RivaSpeechRecognitionGrpc.class) {
        if ((getStreamingRecognizeMethod = RivaSpeechRecognitionGrpc.getStreamingRecognizeMethod) == null) {
          RivaSpeechRecognitionGrpc.getStreamingRecognizeMethod = getStreamingRecognizeMethod =
              io.grpc.MethodDescriptor.<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest, com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "StreamingRecognize"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RivaSpeechRecognitionMethodDescriptorSupplier("StreamingRecognize"))
              .build();
        }
      }
    }
    return getStreamingRecognizeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RivaSpeechRecognitionStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionStub>() {
        @java.lang.Override
        public RivaSpeechRecognitionStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RivaSpeechRecognitionStub(channel, callOptions);
        }
      };
    return RivaSpeechRecognitionStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static RivaSpeechRecognitionBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionBlockingV2Stub>() {
        @java.lang.Override
        public RivaSpeechRecognitionBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RivaSpeechRecognitionBlockingV2Stub(channel, callOptions);
        }
      };
    return RivaSpeechRecognitionBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RivaSpeechRecognitionBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionBlockingStub>() {
        @java.lang.Override
        public RivaSpeechRecognitionBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RivaSpeechRecognitionBlockingStub(channel, callOptions);
        }
      };
    return RivaSpeechRecognitionBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RivaSpeechRecognitionFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RivaSpeechRecognitionFutureStub>() {
        @java.lang.Override
        public RivaSpeechRecognitionFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RivaSpeechRecognitionFutureStub(channel, callOptions);
        }
      };
    return RivaSpeechRecognitionFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void recognize(com.neo.dashboard.asr.riva.proto.RecognizeRequest request,
        io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.RecognizeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRecognizeMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest> streamingRecognize(
        io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getStreamingRecognizeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RivaSpeechRecognition.
   */
  public static abstract class RivaSpeechRecognitionImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RivaSpeechRecognitionGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RivaSpeechRecognition.
   */
  public static final class RivaSpeechRecognitionStub
      extends io.grpc.stub.AbstractAsyncStub<RivaSpeechRecognitionStub> {
    private RivaSpeechRecognitionStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RivaSpeechRecognitionStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RivaSpeechRecognitionStub(channel, callOptions);
    }

    /**
     */
    public void recognize(com.neo.dashboard.asr.riva.proto.RecognizeRequest request,
        io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.RecognizeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRecognizeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest> streamingRecognize(
        io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getStreamingRecognizeMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RivaSpeechRecognition.
   */
  public static final class RivaSpeechRecognitionBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<RivaSpeechRecognitionBlockingV2Stub> {
    private RivaSpeechRecognitionBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RivaSpeechRecognitionBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RivaSpeechRecognitionBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.neo.dashboard.asr.riva.proto.RecognizeResponse recognize(com.neo.dashboard.asr.riva.proto.RecognizeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRecognizeMethod(), getCallOptions(), request);
    }

    /**
     */
    @io.grpc.ExperimentalApi("https://github.com/grpc/grpc-java/issues/10918")
    public io.grpc.stub.BlockingClientCall<com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest, com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse>
        streamingRecognize() {
      return io.grpc.stub.ClientCalls.blockingBidiStreamingCall(
          getChannel(), getStreamingRecognizeMethod(), getCallOptions());
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service RivaSpeechRecognition.
   */
  public static final class RivaSpeechRecognitionBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RivaSpeechRecognitionBlockingStub> {
    private RivaSpeechRecognitionBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RivaSpeechRecognitionBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RivaSpeechRecognitionBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.neo.dashboard.asr.riva.proto.RecognizeResponse recognize(com.neo.dashboard.asr.riva.proto.RecognizeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRecognizeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RivaSpeechRecognition.
   */
  public static final class RivaSpeechRecognitionFutureStub
      extends io.grpc.stub.AbstractFutureStub<RivaSpeechRecognitionFutureStub> {
    private RivaSpeechRecognitionFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RivaSpeechRecognitionFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RivaSpeechRecognitionFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.neo.dashboard.asr.riva.proto.RecognizeResponse> recognize(
        com.neo.dashboard.asr.riva.proto.RecognizeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRecognizeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_RECOGNIZE = 0;
  private static final int METHODID_STREAMING_RECOGNIZE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_RECOGNIZE:
          serviceImpl.recognize((com.neo.dashboard.asr.riva.proto.RecognizeRequest) request,
              (io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.RecognizeResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_STREAMING_RECOGNIZE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.streamingRecognize(
              (io.grpc.stub.StreamObserver<com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getRecognizeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.neo.dashboard.asr.riva.proto.RecognizeRequest,
              com.neo.dashboard.asr.riva.proto.RecognizeResponse>(
                service, METHODID_RECOGNIZE)))
        .addMethod(
          getStreamingRecognizeMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              com.neo.dashboard.asr.riva.proto.StreamingRecognizeRequest,
              com.neo.dashboard.asr.riva.proto.StreamingRecognizeResponse>(
                service, METHODID_STREAMING_RECOGNIZE)))
        .build();
  }

  private static abstract class RivaSpeechRecognitionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RivaSpeechRecognitionBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.neo.dashboard.asr.riva.proto.RivaAsr.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RivaSpeechRecognition");
    }
  }

  private static final class RivaSpeechRecognitionFileDescriptorSupplier
      extends RivaSpeechRecognitionBaseDescriptorSupplier {
    RivaSpeechRecognitionFileDescriptorSupplier() {}
  }

  private static final class RivaSpeechRecognitionMethodDescriptorSupplier
      extends RivaSpeechRecognitionBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RivaSpeechRecognitionMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RivaSpeechRecognitionGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RivaSpeechRecognitionFileDescriptorSupplier())
              .addMethod(getRecognizeMethod())
              .addMethod(getStreamingRecognizeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
