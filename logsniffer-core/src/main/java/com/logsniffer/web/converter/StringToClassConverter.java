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
package com.logsniffer.web.converter;

import org.springframework.core.convert.converter.Converter;

/**
 * Converts a string to {@link Class} representation.
 * 
 * @author mbok
 * 
 */
public class StringToClassConverter implements Converter<String, Class<?>> {

	@Override
	public Class<?> convert(String source) {
		try {
			return Class.forName(source);
		} catch (ClassNotFoundException e) {
			throw new IllegalArgumentException("Can't convert to class: "
					+ source);
		}
	}

}
