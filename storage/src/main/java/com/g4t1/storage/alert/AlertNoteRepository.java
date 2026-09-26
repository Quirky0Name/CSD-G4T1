package com.g4t1.storage.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertNoteRepository extends JpaRepository<AlertNote, Long> {

    List<AlertNote> findByAlertIdOrderByCreatedAtDescIdDesc(Long alertId);
}
