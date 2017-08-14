/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flow.platform.api.service;

import com.flow.platform.api.dao.JobDao;
import com.flow.platform.api.domain.Job;
import com.flow.platform.api.domain.Node;
import com.flow.platform.api.domain.NodeResult;
import com.flow.platform.api.domain.NodeStatus;
import com.flow.platform.api.domain.Step;
import com.flow.platform.api.exception.HttpException;
import com.flow.platform.api.exception.NotFoundException;
import com.flow.platform.api.util.CommonUtil;
import com.flow.platform.api.util.EnvUtil;
import com.flow.platform.api.util.HttpUtil;
import com.flow.platform.api.util.NodeUtil;
import com.flow.platform.api.util.PathUtil;
import com.flow.platform.api.util.UrlUtil;
import com.flow.platform.domain.Cmd;
import com.flow.platform.domain.CmdBase;
import com.flow.platform.domain.CmdInfo;
import com.flow.platform.domain.CmdResult;
import com.flow.platform.domain.CmdStatus;
import com.flow.platform.domain.CmdType;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.exception.IllegalParameterException;
import com.flow.platform.util.Logger;
import com.google.common.base.Strings;
import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author yh@firim
 */
@Service(value = "jobService")
@Transactional
public class JobServiceImpl implements JobService {

    private static Logger LOGGER = new Logger(JobService.class);

    private Integer RETRY_TIMEs = 5;

    @Autowired
    private JobNodeResultService jobNodeResultService;

    @Autowired
    private JobNodeService jobNodeService;

    @Autowired
    private JobDao jobDao;

    @Autowired
    private NodeService nodeService;

    @Value(value = "${domain}")
    private String domain;

    @Value(value = "${platform.zone}")
    private String zone;

    @Value(value = "${platform.cmd.url}")
    private String cmdUrl;

    @Value(value = "${platform.queue.url}")
    private String queueUrl;

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Job createJob(String path) {
        Node root = nodeService.find(PathUtil.rootPath(path));
        if (root == null) {
            throw new IllegalParameterException("Path does not existed");
        }

        String yml = nodeService.getYmlContent(root.getPath());
        if (Strings.isNullOrEmpty(yml)) {
            throw new NotFoundException("Yml content not found for path " + path);
        }

        // create job
        Job job = new Job(CommonUtil.randomId());
        job.setStatus(NodeStatus.PENDING);
        job.setNodePath(root.getPath());
        job.setNodeName(root.getName());
        job.setCreatedAt(ZonedDateTime.now());
        job.setUpdatedAt(ZonedDateTime.now());

        //update number
        job.setNumber(jobDao.maxBuildNumber(job.getNodeName()) + 1);

        //save job
        save(job);

        // create yml snapshot for job
        jobNodeService.save(job.getId(), yml);

        // init for node result
        jobNodeResultService.create(job);

        //create session
        createSession(job);

        return job;
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void callback(String id, CmdBase cmdBase) {
        Job job;
        if (cmdBase.getType() == CmdType.CREATE_SESSION) {

            job = find(new BigInteger(id));
            if (job == null) {
                LOGGER.warn(String.format("job not found, jobId: %s", id));
                throw new RuntimeException("job not found");
            }
            sessionCallback(job, cmdBase);
        } else if (cmdBase.getType() == CmdType.RUN_SHELL) {
            Map<String, String> map = Jsonable.GSON_CONFIG.fromJson(id, Map.class);
            job = find(new BigInteger(map.get("jobId")));
            nodeCallback(map.get("path"), cmdBase, job);
        } else {
            LOGGER.warn(String.format("not found cmdType, cmdType: %s", cmdBase.getType().toString()));
            throw new RuntimeException("not found cmdType");
        }
    }

    /**
     * the reason of transaction to retry 5 times
     */
    public Job retryFindJob(BigInteger id) {
        Job job = null;
        for (Integer i = 0; i < RETRY_TIMEs; i++) {
            job = find(id);
            if (job != null) {
                break;
            }
            try {
                Thread.sleep(5000);
            } catch (Throwable throwable) {
            }
            LOGGER.traceMarker("retryFindJob", String.format("retry times %s", i));
        }
        return job;
    }

    /**
     * run node
     *
     * @param node job node's script and record cmdId and sync send http
     */
    @Override
    public void run(Node node, BigInteger jobId) {
        if (!NodeUtil.canRun(node)) {
            // run next node
            run(NodeUtil.next(node), jobId);
            return;
        }

        Node flow = NodeUtil.findRootNode(node);
        EnvUtil.merge(flow, node, false);

        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.RUN_SHELL, node.getScript());
        cmdInfo.setInputs(node.getEnvs());
        cmdInfo.setWebhook(getNodeHook(node, jobId));
        cmdInfo.setOutputEnvFilter("FLOW_");
        Job job = find(jobId);
        cmdInfo.setSessionId(job.getSessionId());
        LOGGER.traceMarker("run", String.format("stepName - %s, nodePath - %s", node.getName(), node.getPath()));
        try {
            String res = HttpUtil.post(cmdUrl, cmdInfo.toJson());

            if (res == null) {
                LOGGER.warn(String.format("post cmd error, cmdUrl: %s, cmdInfo: %s", cmdUrl, cmdInfo.toJson()));
                throw new HttpException(
                    String.format("Post Cmd Error, Node Name - %s, CmdInfo - %s", node.getName(), cmdInfo.toJson()));
            }

            Cmd cmd = Jsonable.parse(res, Cmd.class);
            NodeResult nodeResult = jobNodeResultService.find(node.getPath(), jobId);

            // record cmd id
            nodeResult.setCmdId(cmd.getId());
            jobNodeResultService.update(nodeResult);
        } catch (Throwable ignore) {
            LOGGER.warn("run step UnsupportedEncodingException", ignore);
        }
    }

    @Override
    public Job save(Job job) {
        jobDao.save(job);
        return job;
    }

    @Override
    public Job find(BigInteger id) {
        return jobDao.get(id);
    }

    @Override
    public Job update(Job job) {
        jobDao.update(job);
        return job;
    }

    /**
     * get job callback
     */
    private String getJobHook(Job job) {
        return domain + "/hooks/cmd?identifier=" + UrlUtil.urlEncoder(job.getId().toString());
    }

    /**
     * get node callback
     */
    private String getNodeHook(Node node, BigInteger jobId) {
        Map<String, String> map = new HashMap<>();
        map.put("path", node.getPath());
        map.put("jobId", jobId.toString());
        return domain + "/hooks/cmd?identifier=" + UrlUtil.urlEncoder(Jsonable.GSON_CONFIG.toJson(map));
    }

    /**
     * create session
     */
    private void createSession(Job job) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.CREATE_SESSION, null);
        LOGGER.traceMarker("createSession", String.format("jobId - %s", job.getId()));
        cmdInfo.setWebhook(getJobHook(job));
        // create session
        Cmd cmd = sendToQueue(cmdInfo);
        //enter queue
        job.setStatus(NodeStatus.ENQUEUE);
        job.setCmdId(cmd.getId());
        update(job);
    }

    /**
     * delete sessionId
     */
    private void deleteSession(Job job) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.DELETE_SESSION, null);
        cmdInfo.setSessionId(job.getSessionId());

        LOGGER.traceMarker("deleteSession", String.format("sessionId - %s", job.getSessionId()));
        // delete session
        sendToQueue(cmdInfo);
    }

    /**
     * send cmd by queue
     */
    private Cmd sendToQueue(CmdInfo cmdInfo) {
        Cmd cmd = null;
        StringBuilder stringBuilder = new StringBuilder(queueUrl);
        stringBuilder.append("?priority=1&retry=5");
        try {
            String res = HttpUtil.post(stringBuilder.toString(), cmdInfo.toJson());

            if (res == null) {
                String message = String
                    .format("post session to queue error, cmdUrl: %s, cmdInfo: %s", stringBuilder.toString(),
                        cmdInfo.toJson());

                LOGGER.warn(message);
                throw new HttpException(message);
            }

            cmd = Jsonable.parse(res, Cmd.class);
        } catch (Throwable ignore) {
            LOGGER.warn("run step UnsupportedEncodingException", ignore);
        }
        return cmd;
    }

    /**
     * session success callback
     */
    private void sessionCallback(Job job, CmdBase cmdBase) {
        if (cmdBase.getStatus() == CmdStatus.SENT) {
            job.setUpdatedAt(ZonedDateTime.now());
            job.setSessionId(cmdBase.getSessionId());
            update(job);
            // run step
            NodeResult nodeResult = jobNodeResultService.find(job.getNodePath(), job.getId());
            Node flow = jobNodeService.get(job.getId(), nodeResult.getNodeResultKey().getPath());

            if (flow == null) {
                throw new NotFoundException(String.format("Not Found Job Flow - %s", flow.getName()));
            }

            // start run flow
            run(NodeUtil.first(flow), job.getId());
        } else {
            LOGGER.warn(String.format("Create Session Error Session Status - %s", cmdBase.getStatus().getName()));
        }
    }

    /**
     * step success callback
     */
    private void nodeCallback(String nodePath, CmdBase cmdBase, Job job) {
        NodeResult nodeResult = jobNodeResultService.find(nodePath, job.getId());
        NodeStatus nodeStatus = handleStatus(cmdBase);

        // keep job step status sorted
        if (nodeResult.getStatus().getLevel() > nodeStatus.getLevel()) {
            return;
        }

        //update job step status
        nodeResult.setStatus(nodeStatus);

        jobNodeResultService.update(nodeResult);

        Node step = jobNodeService.get(job.getId(), nodeResult.getNodeResultKey().getPath());
        //update node status
        updateNodeStatus(step, cmdBase, job);
    }

    /**
     * update job flow status
     */
    private void updateJobStatus(NodeResult nodeResult) {
        Node node = jobNodeService
            .get(nodeResult.getNodeResultKey().getJobId(), nodeResult.getNodeResultKey().getPath());
        Job job = find(nodeResult.getNodeResultKey().getJobId());

        if (node instanceof Step) {
            //merge step outputs in flow outputs
            EnvUtil.merge(nodeResult.getOutputs(), job.getOutputs(), false);
            update(job);
            return;
        }

        job.setUpdatedAt(ZonedDateTime.now());
        job.setExitCode(nodeResult.getExitCode());
        NodeStatus nodeStatus = nodeResult.getStatus();

        if (nodeStatus == NodeStatus.TIMEOUT || nodeStatus == NodeStatus.FAILURE) {
            nodeStatus = NodeStatus.FAILURE;
        }

        job.setStatus(nodeStatus);
        update(job);

        //delete session
        if (nodeStatus == NodeStatus.FAILURE || nodeStatus == NodeStatus.SUCCESS) {
            deleteSession(job);
        }
    }

    /**
     * update node status
     */
    private void updateNodeStatus(Node node, CmdBase cmdBase, Job job) {
        NodeResult nodeResult = jobNodeResultService.find(node.getPath(), job.getId());
        //update jobNode
        nodeResult.setUpdatedAt(ZonedDateTime.now());
        nodeResult.setStatus(handleStatus(cmdBase));
        CmdResult cmdResult = ((Cmd) cmdBase).getCmdResult();

        if (cmdResult != null) {
            nodeResult.setExitCode(cmdResult.getExitValue());
            if (NodeUtil.canRun(node)) {
                nodeResult.setDuration(cmdResult.getDuration());
                nodeResult.setOutputs(cmdResult.getOutput());
                nodeResult.setLogPaths(((Cmd) cmdBase).getLogPaths());
                nodeResult.setStartTime(cmdResult.getStartTime());
                nodeResult.setFinishTime(((Cmd) cmdBase).getFinishedDate());
            }
        }

        Node parent = node.getParent();
        Node prev = node.getPrev();
        Node next = node.getNext();
        switch (nodeResult.getStatus()) {
            case PENDING:
            case RUNNING:
                if (cmdResult != null) {
                    nodeResult.setStartTime(cmdResult.getStartTime());
                }

                if (parent != null) {
                    // first node running update parent node running
                    if (prev == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    }
                }
                break;
            case SUCCESS:
                if (cmdResult != null) {
                    nodeResult.setFinishTime(cmdResult.getFinishTime());
                }

                if (parent != null) {
                    // last node running update parent node running
                    if (next == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    } else {
                        run(NodeUtil.next(node), job.getId());
                    }
                }
                break;
            case TIMEOUT:
            case FAILURE:
                if (cmdResult != null) {
                    nodeResult.setFinishTime(cmdResult.getFinishTime());
                }

                //update parent node if next is not null, if allow failure is false
                if (parent != null && (((Step) node).getAllowFailure())) {
                    if (next == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    }
                }

                //update parent node if next is not null, if allow failure is false
                if (parent != null && !((Step) node).getAllowFailure()) {
                    updateNodeStatus(node.getParent(), cmdBase, job);
                }

                //next node not null, run next node
                if (next != null && ((Step) node).getAllowFailure()) {
                    run(NodeUtil.next(node), job.getId());
                }
                break;
        }

        //update job status
        updateJobStatus(nodeResult);

        //save
        jobNodeResultService.update(nodeResult);
    }

    /**
     * transfer cmdStatus to Job status
     */
    private NodeStatus handleStatus(CmdBase cmdBase) {
        NodeStatus nodeStatus = null;
        switch (cmdBase.getStatus()) {
            case SENT:
            case PENDING:
                nodeStatus = NodeStatus.PENDING;
                break;
            case RUNNING:
            case EXECUTED:
                nodeStatus = NodeStatus.RUNNING;
                break;
            case LOGGED:
                CmdResult cmdResult = ((Cmd) cmdBase).getCmdResult();
                if (cmdResult != null && cmdResult.getExitValue() == 0) {
                    nodeStatus = NodeStatus.SUCCESS;
                } else {
                    nodeStatus = NodeStatus.FAILURE;
                }
                break;
            case KILLED:
            case EXCEPTION:
            case REJECTED:
            case STOPPED:
                nodeStatus = NodeStatus.FAILURE;
                break;
            case TIMEOUT_KILL:
                nodeStatus = NodeStatus.TIMEOUT;
                break;
        }
        return nodeStatus;
    }

    @Override
    public List<Job> listJobs(String flowName, List<String> flowNames) {
        if (flowName == null && flowNames == null) {
            return jobDao.list();
        }

        if (flowNames != null) {
            return jobDao.listLatest(flowNames);
        }

        if (flowName != null) {
            return jobDao.list(flowName);
        }
        return null;
    }

    @Override
    public Job find(String flowName, Integer number) {
        return jobDao.get(flowName, number);
    }
}