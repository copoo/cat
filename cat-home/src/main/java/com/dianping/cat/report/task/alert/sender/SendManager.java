package com.dianping.cat.report.task.alert.sender;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.unidal.helper.Threads;
import org.unidal.helper.Threads.Task;
import org.unidal.lookup.annotation.Inject;
import org.unidal.tuple.Pair;

import com.dianping.cat.Cat;
import com.dianping.cat.report.task.alert.manager.AlertManager;
import com.dianping.cat.report.task.alert.sender.decorator.DecoratorManager;
import com.dianping.cat.report.task.alert.sender.receiver.Contactor;
import com.dianping.cat.report.task.alert.sender.sender.SenderManager;
import com.dianping.cat.system.config.AlertPolicyManager;

public class SendManager implements Initializable {

	@Inject
	private AlertPolicyManager m_policyManager;

	@Inject
	private DecoratorManager m_decoratorManager;

	@Inject
	private Contactor m_contactor;

	@Inject
	protected AlertManager m_alertManager;

	@Inject
	protected SenderManager m_senderManager;

	private BlockingQueue<AlertEntity> m_alerts = new LinkedBlockingDeque<AlertEntity>();

	private boolean send(AlertEntity alert) {
		boolean result = true;
		String type = alert.getType();
		String group = alert.getGroup();
		String level = alert.getLevel();

		String channels = m_policyManager.queryChannels(type, group, level);

		for (AlertChannel channel : AlertChannel.values()) {
			String channelName = channel.getName();
			if (channels.contains(channelName)) {
				Pair<String, String> pair = m_decoratorManager.generateTitleAndContent(alert, channelName);
				List<String> receivers = m_contactor.queryReceivers(alert.getProductline(), channel, type);
				AlertMessageEntity message = new AlertMessageEntity(group, pair.getKey(), pair.getValue(), receivers);

				m_alertManager.storeAlert(alert, message);

				if (!m_senderManager.sendAlert(channelName, type, message)) {
					result = false;
				}
			}
		}

		return result;
	}

	public synchronized boolean addAlert(AlertEntity alert) {
		return m_alerts.offer(alert);
	}

	private class SendExecutor implements Task {
		@Override
		public void run() {
			boolean active = true;

			while (active) {
				try {
					AlertEntity alert = m_alerts.poll(5, TimeUnit.MILLISECONDS);

					if (alert != null) {
						send(alert);
					}
				} catch (Exception e) {
					Cat.logError(e);
					break;
				}
			}
		}

		@Override
		public String getName() {
			return "send-executor";
		}

		@Override
		public void shutdown() {
		}

	}

	@Override
	public void initialize() throws InitializationException {
		Threads.forGroup("Cat").start(new SendExecutor());
	}

}
