/*
 *  Copyright 2019 Expedia, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.expedia.www.haystack.agent.blobs.server.api;

import com.expedia.www.haystack.agent.blobs.dispatcher.core.RateLimitException;
import com.expedia.www.haystack.agent.blobs.grpc.Blob;
import com.expedia.www.haystack.agent.blobs.grpc.api.BlobAgentGrpc;
import com.expedia.www.haystack.agent.blobs.dispatcher.core.BlobDispatcher;
import com.expedia.www.haystack.agent.blobs.grpc.api.DispatchResult;

import io.grpc.stub.StreamObserver;
import org.apache.commons.lang3.StringUtils;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
//TODO: Metrics left
public class BlobAgentGrpcServer extends BlobAgentGrpc.BlobAgentImplBase {
    private final Logger LOGGER = LoggerFactory.getLogger(BlobAgentGrpcServer.class);

    private final List<BlobDispatcher> dispatchers;
    private final int maxBlobSizeInBytes;

    public BlobAgentGrpcServer(final List<BlobDispatcher> dispatchers, final int maxBlobSizeInBytes) {
        Validate.notEmpty(dispatchers, "Dispatchers can't be empty");
        this.maxBlobSizeInBytes = maxBlobSizeInBytes;
        this.dispatchers = dispatchers;

    }

    public void dispatch(final Blob blob,
                         final StreamObserver<DispatchResult> responseObserver) {

        final DispatchResult.Builder result = DispatchResult.newBuilder().setCode(DispatchResult.ResultCode.SUCCESS);
        final StringBuilder failedDispatchers = new StringBuilder();

        final long blobPayloadSize = blob.getContent().size();

        if (blobPayloadSize > maxBlobSizeInBytes) {
            result.setCode(DispatchResult.ResultCode.MAX_SIZE_EXCEEDED_ERROR);
            result.setErrorMessage(
                    String.format("Fail to dispatch as the blob size=%d exceeds the limit of %d bytes",
                            blob.getContent().size(),
                            maxBlobSizeInBytes));
        } else {

            for (final BlobDispatcher d : dispatchers) {
                try {
                    d.dispatch(blob);
                } catch (final RateLimitException r) {
                    result.setCode(DispatchResult.ResultCode.RATE_LIMIT_ERROR);
                    LOGGER.error("Fail to dispatch the blobs due to rate limit errors", r);
                    failedDispatchers.append(d.getName()).append(',');
                } catch (final Exception ex) {
                    result.setCode(DispatchResult.ResultCode.UNKNOWN_ERROR);
                    LOGGER.error("Fail to dispatch the blob to the dispatcher with name={}", d.getName(), ex);
                    failedDispatchers.append(d.getName()).append(',');
                }
            }
        }

        if (failedDispatchers.length() > 0) {
            result.setErrorMessage("Fail to dispatch the blob to the dispatchers=" +
                    StringUtils.removeEnd(failedDispatchers.toString(), ","));
        }

        responseObserver.onNext(result.build());
        responseObserver.onCompleted();
    }
}
