package org.openjproxy.grpc.server.action.transaction;

import com.openjproxy.grpc.XidProto;
import org.openjproxy.grpc.server.XidImpl;

import javax.transaction.xa.Xid;

/**
 * Utility class for converting between Protocol Buffer XID messages and
 * {@link javax.transaction.xa.Xid} instances.
 * <p>
 * Provides bidirectional conversion methods to facilitate XA transaction
 * identifier serialization and deserialization in the gRPC communication layer.
 */
public class XidHelper {

    private XidHelper() {}

    /**
     * Convert protobuf Xid to javax.transaction.xa.Xid.
     */
    public static Xid convertXid(XidProto xidProto) {
        return new XidImpl(
                xidProto.getFormatId(),
                xidProto.getGlobalTransactionId().toByteArray(),
                xidProto.getBranchQualifier().toByteArray()
        );
    }

    /**
     * Convert javax.transaction.xa.Xid to protobuf Xid.
     */
    public static XidProto convertXidToProto(javax.transaction.xa.Xid xid) {
        return XidProto.newBuilder()
                .setFormatId(xid.getFormatId())
                .setGlobalTransactionId(com.google.protobuf.ByteString.copyFrom(xid.getGlobalTransactionId()))
                .setBranchQualifier(com.google.protobuf.ByteString.copyFrom(xid.getBranchQualifier()))
                .build();
    }
}
