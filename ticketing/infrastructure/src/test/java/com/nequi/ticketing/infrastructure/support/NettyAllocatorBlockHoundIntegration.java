package com.nequi.ticketing.infrastructure.support;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * BlockHound allowance for an internal of Netty (NFR-003, plan section 4.3), approved in IV-022 and documented
 * in the INC-010 report; no project code is exempted.
 *
 * <p>{@code io.netty.buffer.AdaptivePoolingAllocator$MagazineGroup#tryExpandMagazines} takes a
 * {@code StampedLock} write lock for a few instructions, without I/O, to grow the set of buffer magazines. When a
 * buffer is allocated or released from a non-blocking thread that is not a Netty event loop (for example, a
 * response written from {@code Schedulers.parallel()}) while event-loop threads use the same allocator, the
 * calling thread may park for that instant. The same kind of allowance as the AWS SDK internals of INC-005
 * ({@code AwsSdkBlockHoundIntegration}).
 */
public final class NettyAllocatorBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside(
                "io.netty.buffer.AdaptivePoolingAllocator$MagazineGroup", "tryExpandMagazines");
    }
}
