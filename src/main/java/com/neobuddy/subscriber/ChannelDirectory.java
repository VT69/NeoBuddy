package com.neobuddy.subscriber;

import com.neobuddy.subscriber.SubscriberView.ChannelView;
import com.neobuddy.subscriber.persistence.Channel;
import com.neobuddy.subscriber.persistence.ChannelRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The channels alerts may be sent to. A channel counts only while it is verified and enabled, so
 * nothing ever goes to an address its owner has not proved or has switched off.
 */
@Service
public class ChannelDirectory {

  private final ChannelRepository channels;

  ChannelDirectory(ChannelRepository channels) {
    this.channels = channels;
  }

  /** The user's verified, enabled channels, without addresses. */
  @Transactional(readOnly = true)
  public List<ChannelView> deliverable(UUID userId) {
    return channels.findByUserIdOrderByType(userId).stream()
        .filter(ChannelDirectory::isDeliverable)
        .map(channel ->
            new ChannelView(
                channel.getId(), channel.getType(), channel.isVerified(), channel.isEnabled()))
        .toList();
  }

  /** The type of a channel, whatever its state; empty if it is gone. */
  @Transactional(readOnly = true)
  public Optional<ChannelType> typeOf(UUID channelId) {
    return channels.findById(channelId).map(Channel::getType);
  }

  /** The address of a channel; empty if it is gone, unverified or switched off. */
  @Transactional(readOnly = true)
  public Optional<ChannelTarget> target(UUID channelId) {
    return channels
        .findById(channelId)
        .filter(ChannelDirectory::isDeliverable)
        .map(channel ->
            new ChannelTarget(
                channel.getId(),
                channel.getUserId(),
                channel.getType(),
                new ChannelAddress(channel.getAddress())));
  }

  /**
   * The address of one of the user's own channels, verified or not, to show it to them (where a
   * code was sent, which topic to subscribe to). Empty if the channel is not theirs.
   */
  @Transactional(readOnly = true)
  public Optional<ChannelAddress> addressOf(UUID userId, UUID channelId) {
    return channels
        .findByIdAndUserId(channelId, userId)
        .map(channel -> new ChannelAddress(channel.getAddress()));
  }

  /**
   * The channel of this type whose address is the given one, verified or not. Addresses are
   * encrypted with a random IV, so this decrypts every channel of the type and compares; fine for
   * the Discord channels of one deployment, not for hot paths.
   */
  @Transactional(readOnly = true)
  public Optional<ChannelTarget> findByAddress(ChannelType type, String address) {
    return channels.findByType(type).stream()
        .filter(channel -> channel.getAddress().equals(address))
        .findFirst()
        .map(channel ->
            new ChannelTarget(
                channel.getId(),
                channel.getUserId(),
                channel.getType(),
                new ChannelAddress(channel.getAddress())));
  }

  private static boolean isDeliverable(Channel channel) {
    return channel.isVerified() && channel.isEnabled();
  }
}
