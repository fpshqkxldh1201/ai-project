package com.jarvisst.Lumi.repository;

import com.jarvisst.Lumi.entity.KnowledgeNote;
import com.jarvisst.Lumi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KnowledgeNoteRepository extends JpaRepository<KnowledgeNote, Long> {

    List<KnowledgeNote> findByOwnerOrderByCreatedAtAsc(User owner);

    long countByOwner(User owner);

    // 본인 소유일 때만 조회/삭제된다(IDOR 방지: 다른 사용자의 id를 넣어도 찾지 못한다)
    Optional<KnowledgeNote> findByIdAndOwner(Long id, User owner);

    @Modifying
    @Query("DELETE FROM KnowledgeNote n WHERE n.owner = :owner")
    int deleteAllByOwner(@Param("owner") User owner);
}
