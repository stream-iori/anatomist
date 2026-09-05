package com.anatomist.cli;

import com.anatomist.query.semantic.SemanticFrameAssembler;
import com.anatomist.query.semantic.SemanticFrameSource;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamReader;
import com.anatomist.query.semantic.SemanticStreamWriter;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

/** Single-threaded, typed, bounded semantic pipeline over one query connection. */
final class SemanticPipelineExecutor {
    private final List<PipelineStageRegistry.Stage> stages;
    private final SemanticExecutionContext context;
    private final SemanticCommand.Result[] totals;
    private final SemanticFrameAssembler[] assemblers;
    private final SemanticStreamWriter finalWriter;

    SemanticPipelineExecutor(List<PipelineStageRegistry.Stage> stages,
                             SemanticExecutionContext context, PrintStream output,
                             String format) {
        this.stages = stages;
        this.context = context;
        this.totals = new SemanticCommand.Result[stages.size()];
        this.assemblers = new SemanticFrameAssembler[Math.max(0, stages.size() - 1)];
        this.finalWriter = new SemanticStreamWriter(output, format);
        for (int index = 0; index < assemblers.length; index++) {
            int next = index + 1;
            assemblers[index] = new SemanticFrameAssembler(context.identity(),
                    frame -> executeFrame(next, frame));
        }
    }

    void execute(InputStream input) {
        try (finalWriter) {
            executeStage(0, SemanticFrameSource.ndjson(input));
            for (SemanticFrameAssembler assembler : assemblers) assembler.finish();
            SemanticCommand.Result result = totals[stages.size() - 1];
            if (result == null) result = new SemanticCommand.Result(0, 0, true, false);
            boolean complete = true;
            for (SemanticCommand.Result total : totals) {
                if (total == null) continue;
                complete &= total.complete();
            }
            finalWriter.write(SemanticRecords.streamEvidence(result.seeds(), result.emitted(),
                    complete, result.truncated(), context.identity()));
        }
    }

    private void executeFrame(int stageIndex, SemanticStreamReader.SeedFrame frame) {
        executeStage(stageIndex, SemanticFrameSource.single(frame));
    }

    private void executeStage(int stageIndex, SemanticFrameSource input) {
        PipelineStageRegistry.Stage stage = stages.get(stageIndex);
        SemanticStreamWriter writer = stageIndex == stages.size() - 1
                ? finalWriter : new SemanticStreamWriter(assemblers[stageIndex]);
        try {
            SemanticCommand.Result result = stage.command().executeStage(context, input, writer);
            totals[stageIndex] = totals[stageIndex] == null
                    ? result : totals[stageIndex].merge(result);
        } catch (SemanticStreamWriter.BrokenPipeException failure) {
            throw failure;
        } catch (PipelineFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw PipelineFailure.stage(stage.position(), stage.name(), failure);
        }
    }
}
