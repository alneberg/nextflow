/*
 * Copyright (c) 2013-2018, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2018, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.ga4gh.tes.executor

import static nextflow.processor.TaskStatus.COMPLETED
import static nextflow.processor.TaskStatus.RUNNING

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.executor.BashWrapperBuilder
import nextflow.ga4gh.tes.client.api.TaskServiceApi
import nextflow.ga4gh.tes.client.model.TesExecutor as TesExecutorModel
import nextflow.ga4gh.tes.client.model.TesInput
import nextflow.ga4gh.tes.client.model.TesOutput
import nextflow.ga4gh.tes.client.model.TesResources
import nextflow.ga4gh.tes.client.model.TesState
import nextflow.ga4gh.tes.client.model.TesTask
import nextflow.processor.TaskConfig
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.processor.TaskStatus
/**
 * Handle execution phases for a task executed by a TES executor
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class TesTaskHandler extends TaskHandler {

    static final private String WORK_DIR = '/work'

    final List<TesState> COMPLETE_STATUSES = [TesState.COMPLETE, TesState.EXECUTOR_ERROR, TesState.SYSTEM_ERROR, TesState.CANCELED]

    final List<TesState> STARTED_STATUSES = [TesState.INITIALIZING, TesState.RUNNING, TesState.PAUSED] + COMPLETE_STATUSES

    final TesExecutor executor

    private final Path exitFile

    private final Path wrapperFile

    private final Path outputFile

    private final Path errorFile

    private final Path logFile

    private final Path scriptFile

    private final Path inputFile

    private final Path stubFile

    private final Path traceFile

    private TaskServiceApi client

    private String requestId

    TesTaskHandler(TaskRun task, TesExecutor executor) {
        super(task)
        this.executor = executor
        this.client = executor.getClient()

        this.logFile = task.workDir.resolve(TaskRun.CMD_LOG)
        this.scriptFile = task.workDir.resolve(TaskRun.CMD_SCRIPT)
        this.inputFile =  task.workDir.resolve(TaskRun.CMD_INFILE)
        this.outputFile = task.workDir.resolve(TaskRun.CMD_OUTFILE)
        this.errorFile = task.workDir.resolve(TaskRun.CMD_ERRFILE)
        this.exitFile = task.workDir.resolve(TaskRun.CMD_EXIT)
        this.wrapperFile = task.workDir.resolve(TaskRun.CMD_RUN)
        this.stubFile = task.workDir.resolve(TaskRun.CMD_STUB)
        this.traceFile = task.workDir.resolve(TaskRun.CMD_TRACE)
    }

    @Override
    boolean checkIfRunning() {

        if( requestId && isSubmitted() ) {
            final response = client.getTask(requestId, null)
            final started = response.state in STARTED_STATUSES
            if( started ) {
                log.trace "[TES] Task started > $task.name"
                status = RUNNING
                return true
            }
        }

        return false
    }

    @Override
    boolean checkIfCompleted() {
        if( !isRunning() ) {
            return false
        }

        final response = client.getTask(requestId, null)
        if( response.state in COMPLETE_STATUSES ) {
            // finalize the task
            log.trace "[TES] Task completed > $task.name"
            task.exitStatus = readExitFile()
            task.stdout = outputFile
            task.stderr = errorFile
            status = COMPLETED
            return true
        }

        return false
    }

    private int readExitFile() {
        try {
            exitFile.text as Integer
        }
        catch( Exception e ) {
            log.trace "[TES] Cannot read exitstatus for task: `$task.name`", e
            return Integer.MAX_VALUE
        }
    }

    @Override
    void kill() {
        if( requestId )
            client.cancelTask(requestId)
        else
            log.trace "[TES] Invalid kill request -- missing requestId"
    }

    @Override
    void submit() {

        // create task wrapper
        final bash = new TesBashBuilder(task)
        bash.build()

        final body = newTesTask()

        // submit the task
        final task = client.createTask(body)
        requestId = task.id
        status = TaskStatus.SUBMITTED
    }

    protected final TesTask newTesTask() {
        // the cmd list to launch it
        def job = new ArrayList(BashWrapperBuilder.BASH) << wrapperFile.getName()
        List cmd = ['/bin/bash','-c', job.join(' ') + " &> $TaskRun.CMD_LOG" ]

        def exec = new TesExecutorModel()
        exec.command = cmd
        exec.image = task.container
        exec.workdir = WORK_DIR

        def body = new TesTask()

        // add task control files
        body.addInputsItem(inItem(scriptFile))
        body.addInputsItem(inItem(wrapperFile))

        // add task input files
        if(inputFile.exists()) body.addInputsItem(inItem(inputFile))
        if(stubFile.exists()) body.addInputsItem(inItem(stubFile))

        task.getInputFilesMap()?.each { String name, Path path ->
            body.addInputsItem(inItem(path,name))
        }

        // add the task output files
        body.addOutputsItem(outItem(outputFile.name))
        body.addOutputsItem(outItem(errorFile.name))
        body.addOutputsItem(outItem(logFile.name))
        body.addOutputsItem(outItem(exitFile.name))

        // set requested resources
        body.setResources(getResources(task.config))

        task.outputFilesNames?.each { fileName ->
            body.addOutputsItem(outItem(fileName))
        }

        // add the executor
        body.executors = [exec]

        return body
    }

    private TesResources getResources(TaskConfig cfg) {
        def res = new TesResources()
        res.cpuCores(cfg.getCpus())
            .ramGb(cfg.getMemory()?.toGiga()) // @TODO only works for >= 1.GB
            .diskGb(cfg.getDisk()?.toGiga())
        log.trace("[TES] Adding resource request: $res")
        // @TODO preemptible
        // @TODO zones
        return res
    }


    private TesInput inItem( Path realPath, String fileName = null) {
        def result = new TesInput()
        result.url = realPath.toUri().toString()
        result.path = fileName ? "$WORK_DIR/$fileName" : "$WORK_DIR/${realPath.getName()}"
        log.trace "[TES] Adding INPUT file: $result"
        return result
    }

    private TesOutput outItem( String fileName ) {
        def result = new TesOutput()
        result.path = "$WORK_DIR/$fileName"
        result.url = task.workDir.resolve(fileName).uri.toString()
        log.trace "[TES] Adding OUTPUT file: $result"
        return result
    }

}