package com.g4t1.storage.evaluation;

import com.g4t1.storage.report.FeedbackChanged;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Once a feedback save or delete has committed, nudges Research Evaluation on another thread, so the
 * researcher's answer never waits for it or depends on it. A rolled-back change sends nothing.
 */
@Component
public class FeedbackNudger {

    private final ResearchEvaluationClient researchEvaluation;
    private final TaskExecutor executor;

    public FeedbackNudger(ResearchEvaluationClient researchEvaluation, TaskExecutor executor) {
        this.researchEvaluation = researchEvaluation;
        this.executor = executor;
    }

    // AFTER_COMMIT is the default phase
    @TransactionalEventListener
    public void feedbackChanged(FeedbackChanged event) {
        executor.execute(() -> researchEvaluation.nudgeFeedback(event.reportId()));
    }
}
