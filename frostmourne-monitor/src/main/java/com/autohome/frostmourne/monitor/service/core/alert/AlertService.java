package com.autohome.frostmourne.monitor.service.core.alert;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.annotation.Resource;

import com.autohome.frostmourne.core.contract.Protocol;
import com.autohome.frostmourne.core.jackson.JacksonUtil;
import com.autohome.frostmourne.monitor.contract.AlertContract;
import com.autohome.frostmourne.monitor.contract.ServiceInfoSimpleContract;
import com.autohome.frostmourne.monitor.contract.enums.AlertType;
import com.autohome.frostmourne.monitor.contract.enums.SendStatus;
import com.autohome.frostmourne.monitor.contract.enums.SilenceStatus;
import com.autohome.frostmourne.monitor.contract.enums.VerifyResult;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.domain.AlarmLog;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.domain.AlertLog;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IAlarmLogRepository;
import com.autohome.frostmourne.monitor.dao.mybatis.frostmourne.repository.IAlertLogRepository;
import com.autohome.frostmourne.monitor.service.account.IAccountService;
import com.autohome.frostmourne.monitor.service.core.execute.AlarmProcessLogger;
import com.autohome.frostmourne.spi.starter.api.IFrostmourneSpiApi;
import com.autohome.frostmourne.spi.starter.model.AccountInfo;
import com.autohome.frostmourne.spi.starter.model.AlarmMessage;
import com.autohome.frostmourne.spi.starter.model.MessageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AlertService implements IAlertService {

    private final static Logger LOGGER = LoggerFactory.getLogger(AlertService.class);

    @Resource
    private IFrostmourneSpiApi frostmourneSpiApi;

    @Resource
    private IAlertLogRepository alertLogRepository;

    @Resource
    private IAccountService accountService;

    @Resource
    private IAlarmLogRepository alarmLogRepository;

    public void alert(AlarmProcessLogger alarmProcessLogger) {
        AlertContract alertContract = alarmProcessLogger.getAlarmContract().getAlertContract();
        List<AccountInfo> recipients = recipients(alertContract.getRecipients(), alarmProcessLogger.getAlarmContract().getServiceInfo());
        if (recipients.size() == 0) {
            LOGGER.error("no recipients, alarmId: " + alarmProcessLogger.getAlarmContract().getId());
            return;
        }

        Long alarmId = alarmProcessLogger.getAlarmContract().getId();
        Optional<AlarmLog> latestAlarmLog = alarmLogRepository.selectLatest(alarmId, null);
        if (!alarmProcessLogger.getAlert()) {
            checkRecover(latestAlarmLog, alarmProcessLogger, recipients);
        } else {
            processProblem(alarmProcessLogger, recipients, latestAlarmLog);
        }
    }

    private void sendAlert(AlarmProcessLogger alarmProcessLogger, List<AccountInfo> recipients, String alertType) {
        alarmLog(alarmProcessLogger);
        AlertContract alertContract = alarmProcessLogger.getAlarmContract().getAlertContract();
        AlarmMessage alarmMessage = new AlarmMessage();
        String alertContent = null;
        if (alertType.equalsIgnoreCase(AlertType.PROBLEM)) {
            alarmMessage.setContent(String.format("消息类型: [问题] %s分钟内持续报警将不重复发送\n%s",
                    alertContract.getSilence(), alarmProcessLogger.getAlertMessage()));
            alertContent = alarmProcessLogger.getAlertMessage();
        } else if (alertType.equalsIgnoreCase(AlertType.RECOVER)) {
            AlertLog alertLog = this.alertLogRepository.selectLatest(alarmProcessLogger.getAlarmContract().getId(), AlertType.PROBLEM, SilenceStatus.NO).get();
            alertContent = "消息类型: [恢复] 请自己检查问题是否解决,上次报警内容如下\n" + alertLog.getContent();
            alarmMessage.setContent(alertContent);
        }
        alarmMessage.setTitle(String.format("[霜之哀伤监控平台][id:%s]%s", alarmProcessLogger.getAlarmContract().getId(), alarmProcessLogger.getAlarmContract().getAlarm_name()));
        alarmMessage.setRecipients(recipients);
        alarmMessage.setWays(alertContract.getWays());
        alarmMessage.setDingHook(alertContract.getDing_robot_hook());
        alarmMessage.setHttpPostEndpoint(alertContract.getHttp_post_url());
        alarmMessage.setWechatHook(alertContract.getWechat_robot_hook());
        Protocol<List<MessageResult>> protocol = frostmourneSpiApi.send(alarmMessage, "frostmourne-monitor");
        if (protocol.getReturncode() != 0) {
            LOGGER.error("error when send alert. protocol: " + JacksonUtil.serialize(protocol));
            return;
        }
        saveAlertLog(alertType, protocol.getResult(), recipients, alarmProcessLogger.getAlarmContract().getId(),
                alertContent, alarmProcessLogger.getAlarmLog().getId());
    }

    private List<AccountInfo> recipients(List<String> accounts, ServiceInfoSimpleContract serviceInfoSimpleContract) {
        List<AccountInfo> recipients = new ArrayList<>();
        if (serviceInfoSimpleContract != null && !accounts.contains(serviceInfoSimpleContract.getOwner())) {
            accounts.add(serviceInfoSimpleContract.getOwner());
        }
        for (String userName : accounts) {
            Optional<AccountInfo> accountInfo = accountService.findByAccount(userName);
            accountInfo.ifPresent(recipients::add);
        }
        return recipients;
    }

    private void saveAlertLog(String alertType, List<MessageResult> messageResults,
                              List<AccountInfo> accountInfos, Long alarmId, String content, Long executeId) {
        for (MessageResult messageResult : messageResults) {
            for (AccountInfo accountInfo : accountInfos) {
                AlertLog alertLog = new AlertLog();
                alertLog.setAlarm_id(alarmId);
                alertLog.setContent(content);
                alertLog.setExecute_id(executeId);
                alertLog.setCreate_at(new Date());
                alertLog.setRecipient(accountInfo.getAccount());
                alertLog.setSend_status(messageResult.getSuccess() == 1 ? SendStatus.SUCCESS : SendStatus.FAIL);
                alertLog.setWay(messageResult.getWay());
                alertLog.setAlert_type(alertType);
                alertLog.setIn_silence(SilenceStatus.NO);
                alertLogRepository.insert(alertLog);
            }
        }
    }

    private void checkRecover(Optional<AlarmLog> latestAlarmLog, AlarmProcessLogger alarmProcessLogger, List<AccountInfo> recipients) {
        //if not alert, check if send recover message
        if (latestAlarmLog.isPresent() && latestAlarmLog.get().getVerify_result().equalsIgnoreCase(VerifyResult.TRUE)) {
            //this is recover message
            sendAlert(alarmProcessLogger, recipients, AlertType.RECOVER);
        } else {
            alarmLog(alarmProcessLogger);
        }
    }

    private boolean checkSilence(AlarmProcessLogger alarmProcessLogger) {
        Long alarmId = alarmProcessLogger.getAlarmContract().getId();
        Optional<AlarmLog> latestNoProblemAlarmLog = alarmLogRepository.selectLatest(alarmId, VerifyResult.FALSE);
        Long current = System.currentTimeMillis();
        Long silenceThreshold = alarmProcessLogger.getAlarmContract().getAlertContract().getSilence() * 60 * 1000;
        if (latestNoProblemAlarmLog.isPresent() && current - latestNoProblemAlarmLog.get().getCreate_at().getTime() < silenceThreshold) {
            return true;
        }
        //find latest problem and not silence alert time
        Optional<AlertLog> latestAlertLog = alertLogRepository.selectLatest(alarmId, AlertType.PROBLEM, SilenceStatus.NO);
        if (latestAlertLog.isPresent() && current - latestAlertLog.get().getCreate_at().getTime() < silenceThreshold) {
            return true;
        }

        return false;
    }

    private void saveSilenceAlertLog(AlarmProcessLogger alarmProcessLogger, List<AccountInfo> recipients) {
        alarmLog(alarmProcessLogger);
        Long alarmId = alarmProcessLogger.getAlarmContract().getId();
        AlertContract alertContract = alarmProcessLogger.getAlarmContract().getAlertContract();
        for (String way : alertContract.getWays()) {
            for (AccountInfo accountInfo : recipients) {
                AlertLog alertLog = new AlertLog();
                alertLog.setAlarm_id(alarmId);
                alertLog.setContent(alarmProcessLogger.getAlertMessage());
                alertLog.setExecute_id(alarmProcessLogger.getAlarmLog().getId());
                alertLog.setCreate_at(new Date());
                alertLog.setRecipient(accountInfo.getAccount());
                alertLog.setSend_status(SendStatus.NONE);
                alertLog.setWay(way);
                alertLog.setAlert_type(AlertType.PROBLEM);
                alertLog.setIn_silence(SilenceStatus.YES);
                alertLogRepository.insert(alertLog);
            }
        }
    }

    private void processProblem(AlarmProcessLogger alarmProcessLogger, List<AccountInfo> recipients, Optional<AlarmLog> latestAlarmLog) {
        if (!latestAlarmLog.isPresent() || latestAlarmLog.get().getVerify_result().equalsIgnoreCase(VerifyResult.FALSE)) {
            sendAlert(alarmProcessLogger, recipients, AlertType.PROBLEM);
            return;
        }
        boolean inSilence = checkSilence(alarmProcessLogger);
        if (inSilence) {
            saveSilenceAlertLog(alarmProcessLogger, recipients);
        } else {
            sendAlert(alarmProcessLogger, recipients, AlertType.PROBLEM);
        }
    }

    @Override
    public void alarmLog(AlarmProcessLogger alarmProcessLogger) {
        AlarmLog alarmLog = new AlarmLog();
        alarmLog.setAlarm_id(alarmProcessLogger.getAlarmContract().getId());
        alarmLog.setCost((int) (alarmProcessLogger.getEnd().getMillis() - alarmProcessLogger.getStart().getMillis()));
        alarmLog.setCreate_at(new Date());
        alarmLog.setExe_start(alarmProcessLogger.getStart().toDate());
        alarmLog.setExe_end(alarmProcessLogger.getEnd().toDate());
        alarmLog.setExecute_result(alarmProcessLogger.getExecuteStatus().getName());
        alarmLog.setMessage(alarmProcessLogger.traceInfo());
        Boolean alert = alarmProcessLogger.getAlert();
        alarmLog.setVerify_result(alert != null && alert ? VerifyResult.TRUE : VerifyResult.FALSE);
        alarmLogRepository.insert(alarmLog);
        alarmProcessLogger.setAlarmLog(alarmLog);
    }
}
