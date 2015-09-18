/*******************************************************************************
 * logsniffer, open source tool for viewing, monitoring and analysing log data.
 * Copyright (c) 2015 Scaleborn UG, www.scaleborn.com
 *
 * logsniffer is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * logsniffer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package com.logsniffer.web.controller.sniffer;

import java.io.IOException;
import java.text.ParseException;
import java.util.Map;
import java.util.TreeMap;

import javax.servlet.ServletRequest;

import org.apache.commons.lang.StringUtils;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.ServletRequestUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.logsniffer.event.IncrementData;
import com.logsniffer.event.Sniffer;
import com.logsniffer.model.Log;
import com.logsniffer.model.LogInputStream;
import com.logsniffer.model.LogPointer;
import com.logsniffer.model.LogPointerTransfer;
import com.logsniffer.model.LogRawAccess;
import com.logsniffer.model.LogSource;
import com.logsniffer.web.ViewController;
import com.logsniffer.web.controller.exception.ResourceNotFoundException;

/**
 * Controller for sniffer state and scheduling issues.
 * 
 * @author mbok
 * 
 */
@ViewController
public class SnifferStatusController extends SniffersBaseController {
	private static Logger logger = LoggerFactory
			.getLogger(SnifferStatusController.class);

	public static class LogSniffingStatus {
		private final LogPointer nextOffset;
		private final long logSize;
		private final Log log;
		private final LogPointerTransfer pointerTpl;

		public LogSniffingStatus(final Log log, final LogPointer nextOffset,
				final long logSize, final LogPointerTransfer pointerTpl) {
			super();
			this.nextOffset = nextOffset;
			this.logSize = logSize;
			this.log = log;
			this.pointerTpl = pointerTpl;
		}

		/**
		 * @return the nextOffset
		 */
		public LogPointer getNextOffset() {
			return nextOffset;
		}

		/**
		 * @return the logSize
		 */
		public long getLogSize() {
			return logSize;
		}

		/**
		 * @return the log
		 */
		public Log getLog() {
			return log;
		}

		/**
		 * @return the pointerTpl
		 */
		public LogPointerTransfer getPointerTpl() {
			return pointerTpl;
		}

	}

	@RequestMapping(value = "/sniffers/{snifferId}/status", method = RequestMethod.GET)
	String showState(@PathVariable("snifferId") final long snifferId,
			final Model model) throws ResourceNotFoundException,
			SchedulerException, IOException {
		Sniffer activeSniffer = getAndBindActiveSniffer(model, snifferId);
		LogSource<LogInputStream> logSource = getLogSource(activeSniffer
				.getLogSourceId());
		Map<Log, IncrementData> logsIncData = snifferPersistence
				.getIncrementDataByLog(activeSniffer, logSource);
		TreeMap<String, LogSniffingStatus> logsStatus = new TreeMap<String, LogSniffingStatus>();
		for (Log log : logsIncData.keySet()) {
			LogRawAccess<LogInputStream> logAccess = logSource
					.getLogAccess(log);
			IncrementData incData = logsIncData.get(log);
			LogPointer nextOffset = null;
			if (incData.getNextOffset() != null) {
				nextOffset = logAccess.createRelative(logAccess
						.getFromJSON(incData.getNextOffset().getJson()), 0);
			}
			logsStatus.put(log.getPath(),
					new LogSniffingStatus(log, nextOffset, log.getSize(),
							logAccess.createRelative(null, 1)));
		}
		model.addAttribute("scheduleInfo",
				snifferScheduler.getScheduleInfo(snifferId));
		model.addAttribute("logsStatus", logsStatus);
		return "sniffers/status";
	}

	@RequestMapping(value = "/sniffers/{snifferId}/startForm", method = RequestMethod.POST)
	@Transactional(rollbackFor = Exception.class)
	String start(@PathVariable("snifferId") final long snifferId,
			final ServletRequest request, final Model model,
			final RedirectAttributes redirectAttrs)
			throws ResourceNotFoundException, SchedulerException,
			ParseException, IOException, ServletRequestBindingException {
		logger.info("Starting sniffer: {}", snifferId);
		Sniffer activeSniffer = getAndBindActiveSniffer(model, snifferId);
		LogSource<LogInputStream> source = sourceProvider
				.getSourceById(activeSniffer.getLogSourceId());
		for (Log log : source.getLogs()) {
			String newPos = ServletRequestUtils.getStringParameter(request,
					"newPositions['" + log.getPath() + "']");
			logger.debug("Received new position to start sniffing {} from: {}",
					log.getPath(), newPos);
			if (StringUtils.isNotEmpty(newPos)) {
				LogPointer p = source.getLogAccess(log).getFromJSON(newPos);
				IncrementData incData = snifferPersistence.getIncrementData(
						activeSniffer, source, log);
				incData.setNextOffset(p);
				snifferPersistence.storeIncrementalData(activeSniffer, source,
						log, incData);
				logger.debug("Set new position to start sniffing {} from: {}",
						log.getPath(), newPos);
			}
		}

		snifferScheduler.startSniffing(activeSniffer.getId());
		logger.info("Started sniffer: {}", snifferId);
		redirectAttrs.addFlashAttribute("started", true);
		return "redirect:status";
	}

	@RequestMapping(value = "/sniffers/{snifferId}/stopForm", method = RequestMethod.POST)
	String stop(@PathVariable("snifferId") final long snifferId,
			final Model model, final RedirectAttributes redirectAttrs)
			throws ResourceNotFoundException, SchedulerException {
		logger.info("Stopping sniffer: {}", snifferId);
		Sniffer activeSniffer = getAndBindActiveSniffer(model, snifferId);
		snifferScheduler.stopSniffing(activeSniffer.getId());
		logger.info("Stopped sniffer: {}", snifferId);
		redirectAttrs.addFlashAttribute("stopped", true);
		return "redirect:status";
	}
}
