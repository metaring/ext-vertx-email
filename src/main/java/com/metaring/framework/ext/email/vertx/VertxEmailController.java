package com.metaring.framework.ext.email.vertx;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import com.metaring.framework.SysKB;
import com.metaring.framework.email.EmailContact;
import com.metaring.framework.email.EmailContactSeries;
import com.metaring.framework.email.EmailController;
import com.metaring.framework.email.EmailMessage;
import com.metaring.framework.email.EmailMessageSeries;
import com.metaring.framework.email.EmailTypeEnumerator;
import com.metaring.framework.exception.ManagedException;
import com.metaring.framework.ext.vertx.VertxUtilities;
import com.metaring.framework.functionality.UnmanagedException;
import com.metaring.framework.type.DataRepresentation;
import com.metaring.framework.util.StringUtil;

public class VertxEmailController extends AbstractVerticle implements EmailController {

    private static boolean LOADED = false;

    public static final String MAIL_CLIENT_NAME = "com.metaring.framework.mail.vertx";
    public static final String EVENT_BUS_ADDRESS = "com.metaring.framework.mail.vertx.send";
    private MailClient mailClient;
    private boolean test;
    private String mailTestAddress;
    private final EmailMessageSeries cache = EmailMessageSeries.create();
    @Override
    public void start() throws Exception {
        try {
            test = this.config().getBoolean(CFG_TEST);
        }
        catch (Exception e) {
        }
        try {
            mailTestAddress = this.config().getString(CFG_TEST_ADDRESS);
        }
        catch (Exception e) {
        }
        this.mailClient = MailClient.createShared(this.vertx, new MailConfig(this.config()));
        this.vertx.eventBus().consumer(EVENT_BUS_ADDRESS, this::internalSend);
    }

    public void stop() throws Exception {
        if (this.mailClient != null) {
            this.mailClient.close();
        }
        this.mailClient = null;
    }

    private final void internalSend(Message<String> message) {
        this.sendWork(this.toMailMessage((String) message.body()));
    }

    private final void sendWork(LinkedList<MailMessage> list) {
        MailMessage mailMessage;
        if (!(list == null || list.isEmpty() || (mailMessage = list.removeFirst()) == null || test)) {
            this.mailClient.sendMail(mailMessage, result -> {
                if (result.cause() != null) {
                    result.cause().printStackTrace();
                }
                this.sendWork(list);
            });
        }
    }

    private final LinkedList<MailMessage> toMailMessage(String message) {
        EmailMessageSeries emailMessageSeries = EmailMessageSeries.fromJson(message);
        LinkedList<MailMessage> list = new LinkedList<MailMessage>();
        for (int i = 0; i < emailMessageSeries.size(); ++i) {
            EmailMessage emailMessage = emailMessageSeries.get(i);
            MailMessage mailMessage = new MailMessage();
            mailMessage.setFrom(this.getContact(emailMessage.getFrom()));
            if (mailTestAddress != null) {
                mailMessage.setTo(mailTestAddress);
            }
            else {
                mailMessage.setTo(this.getContacts(emailMessage.getTos()));
                mailMessage.setCc(this.getContacts(emailMessage.getCcs()));
                mailMessage.setBcc(this.getContacts(emailMessage.getBccs()));
            }
            mailMessage.setSubject(emailMessage.getSubject());
            if (emailMessage.getType() == EmailTypeEnumerator.HTML) {
                mailMessage.setHtml(emailMessage.getMessage());
            }
            else {
                mailMessage.setText(emailMessage.getMessage());
            }
            list.add(mailMessage);
        }
        return list;
    }

    private final String getContact(EmailContact emailContact) {
        String contact = "";
        if (emailContact.getName() != null) {
            contact = contact + emailContact.getName();
        }
        if (emailContact.getSurname() != null) {
            if (!contact.isEmpty()) {
                contact = contact + " ";
            }
            contact = contact + emailContact.getSurname();
        }
        if (!contact.isEmpty()) {
            contact = contact + " ";
        }
        String mail = null;
        try {
            mail = emailContact.getMail().toJson().replace("\"", "");
        }
        catch(Exception e) { }
        contact = contact + "<" + mail + ">";
        return contact;
    }

    private final List<String> getContacts(EmailContactSeries emailContactSeries) {
        if (emailContactSeries == null || emailContactSeries.size() == 0) {
            return null;
        }
        ArrayList<String> list = new ArrayList<String>();
        for (int i = 0; i < emailContactSeries.size(); ++i) {
            list.add(this.getContact(emailContactSeries.get(i)));
        }
        return list;
    }

    @Override
    public void reinit(SysKB sysKB) {
        DataRepresentation emailController = sysKB.get(CFG_EMAIL);
        JsonObject config = new JsonObject();
        try {
            config.put("address", emailController.get(CFG_SENDER).getText("mail"));
        }
        catch (Exception e) {
        }
        try {
            config.put("username", emailController.getText(CFG_USERNAME));
        }
        catch (Exception e) {
        }
        try {
            config.put("password", emailController.getText(CFG_PASSWORD));
        }
        catch (Exception e) {
        }
        try {
            Boolean test = emailController.getTruth(CFG_TEST);
            if(test != null) {
                config.put(CFG_TEST, test);
            }
        }
        catch (Exception e) {
        }
        try {
            String testAddress = emailController.getText(CFG_TEST_ADDRESS);
            if(!StringUtil.isNullOrEmpty(testAddress)) {
                config.put(CFG_TEST_ADDRESS, testAddress);
            }
        }
        catch (Exception e) {
        }
        try {
            config.put("hostname", emailController.getText("mail.smtp.host"));
        }
        catch (Exception e) {
        }
        try {
            config.put("port", emailController.getDigit("mail.smtp.port"));
        }
        catch (Exception e) {
        }
        try {
            config.put("login", "REQUIRED");
        }
        catch (Exception e) {
        }
        try {
            config.put("ssl", emailController.getTruth("mail.smtps.ssl.enable"));
        }
        catch (Exception e) {
        }
        try {
            config.put("auth", emailController.getTruth("mail.smtp.auth"));
        }
        catch (Exception e) {
        }
        DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(config);
        if (LOADED) {
            vertx.undeploy(VertxEmailController.class.getName(), undeploy -> {
                LOADED = !undeploy.succeeded();
                if (undeploy.cause() != null) {
                    undeploy.cause().printStackTrace();
                }
                else
                    if (undeploy.succeeded()) {
                        vertx.deployVerticle(VertxEmailController.class.getName(), deploymentOptions, deploy -> {
                            LOADED = deploy.succeeded();
                            if (deploy.cause() != null) {
                                deploy.cause().printStackTrace();
                            }
                            trySendCache();
                        });
                    }
            });
        }
        else {
            VertxUtilities.INSTANCE.deployVerticle(VertxEmailController.class.getName(), deploymentOptions, deploy -> {
                LOADED = deploy.succeeded();
                if (deploy.cause() != null) {
                    deploy.cause().printStackTrace();
                }
                trySendCache();
            });
        }
    }

    @Override
    public void send(EmailMessageSeries emailMessageSeries) throws ManagedException, UnmanagedException {
        if(!LOADED) {
            cache.addAll(emailMessageSeries);
            return;
        }
        VertxUtilities.INSTANCE.eventBus().send(EVENT_BUS_ADDRESS, emailMessageSeries.toJson());
    }

    @Override
    public void shutdown() {
        if (LOADED) {
            vertx.undeploy(VertxEmailController.class.getName(), undeploy -> {
                LOADED = false;
            });
        }
    }

    private void trySendCache() {
        if(!LOADED) {
            return;
        }
        EmailMessageSeries queue = EmailMessageSeries.create(cache);
        cache.clear();
        try {
            send(queue);
        }
        catch(Exception e) {
            e.printStackTrace();
        }
    }
}
