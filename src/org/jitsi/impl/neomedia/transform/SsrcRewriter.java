/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.transform;

import java.util.*;
import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.service.neomedia.*;

/**
 * Rewrites SSRCs and sequence numbers of a given source SSRC. This
 * class is not thread-safe.
 */
class SsrcRewriter
{
    /**
     * The origin SSRC that this <tt>SsrcRewriter</tt> rewrites. The
     * target SSRC is managed by the parent <tt>SsrcGroupRewriter</tt>.
     */
    private final int sourceSSRC;

    public final SsrcGroupRewriter ssrcGroupRewriter;

    /**
     * A <tt>NavigableMap</tt> that maps <tt>Integer</tt>s representing
     * interval maxes to <tt>ExtendedSequenceNumberInterval</tt>s. So,
     * when we receive an RTP packet with given sequence number, we can
     * easily find in which sequence number interval it belongs, if it
     * does.
     *
     * TODO we should not keep more intervals than what's enough to
     * cover the last 1000 (arbitrary value) sequence numbers (and even
     * that's way too much).
     */
    private final NavigableMap<Integer, ExtendedSequenceNumberInterval>
        intervals
            = new TreeMap<>();

    /**
     * This is the current sequence number interval for this origin
     * SSRC. We can't have it in the intervals navigable map because
     * its max isn't determined yet. If this is null, then it means that
     * this original SSRC is paused (invariant).
     */
    private ExtendedSequenceNumberInterval currentExtendedSequenceNumberInterval;

    /**
     * Ctor.
     *
     * @param ssrcGroupRewriter
     * @param sourceSSRC
     */
    public SsrcRewriter(SsrcGroupRewriter ssrcGroupRewriter, int sourceSSRC)
    {
        this.ssrcGroupRewriter = ssrcGroupRewriter;
        this.sourceSSRC = sourceSSRC;
    }

    public Collection<ExtendedSequenceNumberInterval>
        getExtendedSequenceNumberIntervals()
    {
        return intervals.values();
    }

    /**
     *
     * @return
     */
    public ExtendedSequenceNumberInterval
        getCurrentExtendedSequenceNumberInterval()
    {
        return currentExtendedSequenceNumberInterval;
    }

    /**
     * Gets the source SSRC for this <tt>SsrcRewriter</tt>.
     */
    public int getSourceSSRC()
    {
        return this.sourceSSRC;
    }

    /**
     * Rewrites (the SSRC, sequence number, timestamp, etc. of) a specific RTP
     * packet.
     *
     * @param pkt the {@code RawPacket} which represents the RTP packet to be
     * rewritten
     */
    public RawPacket rewriteRTP(RawPacket pkt)
    {
        short seqnum = (short) pkt.getSequenceNumber();
        int extendedSeqnum = extendOriginalSequenceNumber(seqnum);

        // first, check if this is a retransmission and rewrite using
        // an appropriate interval.
        ExtendedSequenceNumberInterval retransmissionInterval
            = findRetransmissionInterval(extendedSeqnum);

        if (retransmissionInterval != null)
        {
            if (SsrcRewritingEngine.logger.isDebugEnabled())
            {
                logDebug(
                        "Retransmitting packet with SEQNUM " + (seqnum & 0xffff)
                            + " of SSRC " + pkt.getSSRCAsLong()
                            + " from the current interval.");
            }

            return
                retransmissionInterval.rewriteRTP(
                        pkt,
                        /* retransmission */ true);
        }

        // this is not a retransmission.

        if (currentExtendedSequenceNumberInterval == null)
        {
            // the stream has resumed.
            currentExtendedSequenceNumberInterval
                = new ExtendedSequenceNumberInterval(
                        this,
                        extendedSeqnum,
                        ssrcGroupRewriter.currentExtendedSeqnumBase);
        }
        else
        {
            // more packets to the stream, increase the sequence number interval
            // range.
            currentExtendedSequenceNumberInterval.extendedMaxOrig
                = extendedSeqnum;
            // the timestamp needs to be greater or equal to the maxTimestamp
            // for the current extended sequence number interval.
            currentExtendedSequenceNumberInterval.maxTimestamp
                = pkt.getTimestamp();
        }
        currentExtendedSequenceNumberInterval.lastSeen
            = System.currentTimeMillis();

        return
            currentExtendedSequenceNumberInterval.rewriteRTP(
                    pkt,
                    /* retransmission */ false);
    }

    /**
     * Moves the current sequence number interval in the {@link #intervals}
     * tree. It is not to be updated anymore.
     */
    public void pause()
    {
        if (currentExtendedSequenceNumberInterval != null)
        {
            intervals.put(
                    currentExtendedSequenceNumberInterval.extendedMaxOrig,
                    currentExtendedSequenceNumberInterval);
            // Store the max timestamp so that we can consult it when we rewrite
            // the next packets of the next stream.
            ssrcGroupRewriter.maxTimestamp
                = currentExtendedSequenceNumberInterval.maxTimestamp;
            currentExtendedSequenceNumberInterval = null;

            // TODO We don't need to keep track of more than 2 cycles, so we
            // need to trim the intervals tree to accommodate just that.
        }
        else
        {
            // this stream is already paused.
            logInfo("The stream is already paused.");
        }
    }

    /**
     *
     * @param origExtendedSeqnum the original extended sequence number.
     * @return
     */
    public ExtendedSequenceNumberInterval findRetransmissionInterval(
            int origExtendedSeqnum)
    {
        // first check in the current sequence number interval.
        if (currentExtendedSequenceNumberInterval != null
                && currentExtendedSequenceNumberInterval.contains(
                        origExtendedSeqnum))
        {
            return currentExtendedSequenceNumberInterval;
        }

        // not there, try to find the sequence number in a previous
        // interval.
        Map.Entry<Integer, ExtendedSequenceNumberInterval> candidateEntry
            = intervals.ceilingEntry(origExtendedSeqnum);

        if (candidateEntry != null)
        {
            ExtendedSequenceNumberInterval candidateInterval
                = candidateEntry.getValue();

            if (candidateInterval.contains(origExtendedSeqnum))
            {
                return candidateInterval;
            }
        }

        return null;
    }

    /**
     *
     * @param ssOrigSeqnum
     * @return
     */
    int extendOriginalSequenceNumber(short ssOrigSeqnum)
    {
        SSRCCache ssrcCache
            = getMediaStream().getStreamRTPManager().getSSRCCache();
        int usOrigSeqnum = ssOrigSeqnum & 0x0000ffff;

        if (ssrcCache != null)
        {
            // XXX We make sure in BasicRTCPTerminationStrategy that the
            // SSRCCache exists so we do the same here.

            SSRCInfo sourceSSRCInfo = ssrcCache.cache.get(getSourceSSRC());

            if (sourceSSRCInfo != null)
                return sourceSSRCInfo.extendSequenceNumber(usOrigSeqnum);
        }
        return usOrigSeqnum;
    }

    /**
     * Gets the {@code MediaStream} associated with this instance.
     *
     * @return the {@code MediaStream} associated with this instance
     */
    public MediaStream getMediaStream()
    {
        return ssrcGroupRewriter.getMediaStream();
    }

    /**
     * Gets the {@code SsrcRewritingEngine} associated with this instance.
     *
     * @return the {@code SsrcRewritingEngine} associated with this instance
     */
    public SsrcRewritingEngine getSsrcRewritingEngine()
    {
        return ssrcGroupRewriter.ssrcRewritingEngine;
    }

    void logDebug(String msg)
    {
        ssrcGroupRewriter.logDebug(msg);
    }

    void logInfo(String msg)
    {
        ssrcGroupRewriter.logInfo(msg);
    }

    void logWarn(String msg)
    {
        ssrcGroupRewriter.logWarn(msg);
    }
}
