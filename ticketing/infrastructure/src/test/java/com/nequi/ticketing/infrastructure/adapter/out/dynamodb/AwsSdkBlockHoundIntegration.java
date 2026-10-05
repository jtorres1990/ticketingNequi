package com.nequi.ticketing.infrastructure.adapter.out.dynamodb;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * The only BlockHound allowances of the build (NFR-003, plan §4.3), all for internals of the AWS SDK and
 * documented in the INC-005 report; no project code is exempted:
 * <ul>
 *   <li>Request signing reuses {@code MessageDigest} instances from a {@code LinkedBlockingDeque} pool
 *       ({@code DigestAlgorithm#getDigest} takes one, {@code CloseableMessageDigest#close} returns it). The
 *       deque guards each offer and poll with a {@code ReentrantLock} held for a few instructions and
 *       without I/O; under contention the calling thread may park for that instant.</li>
 *   <li>Closing an asynchronous client closes its Netty channel pools from Netty's global event executor
 *       with {@code SimpleChannelPool#close}, which waits for the channels to close (shutdown path only).</li>
 * </ul>
 */
public final class AwsSdkBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("software.amazon.awssdk.checksums.internal.DigestAlgorithm", "getDigest");
        builder.allowBlockingCallsInside(
                "software.amazon.awssdk.checksums.internal.DigestAlgorithm$CloseableMessageDigest", "close");
        builder.allowBlockingCallsInside("io.netty.channel.pool.SimpleChannelPool", "close");
    }
}
