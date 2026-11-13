package com.neobuddy.subscriber.persistence;

import com.neobuddy.subscriber.ChannelType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LinkCodeRepository extends JpaRepository<LinkCode, String> {

  List<LinkCode> findByUserId(UUID userId);

  /** The user's codes for one channel type, row-locked until the transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from LinkCode c where c.userId = :userId and c.channelType = :type")
  List<LinkCode> findForUpdate(@Param("userId") UUID userId, @Param("type") ChannelType type);

  @Modifying(flushAutomatically = true, clearAutomatically = false)
  @Query("delete from LinkCode c where c.userId = :userId and c.channelType = :type")
  int deleteByUserIdAndChannelType(@Param("userId") UUID userId, @Param("type") ChannelType type);
}
