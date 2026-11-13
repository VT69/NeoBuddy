package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.ChannelType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {

  List<Channel> findByUserIdOrderByType(UUID userId);

  Optional<Channel> findByIdAndUserId(UUID id, UUID userId);

  Optional<Channel> findByUserIdAndType(UUID userId, ChannelType type);
}
