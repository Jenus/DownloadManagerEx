/**
 * 
 */
package com.snda.mymarket.providers.downloads;

/**
 * @author dongjenus
 * 
 */
public class DateUtils {
	/**
	 * Tests for and adds one component of the duration format.
	 * 
	 * @param builder
	 *            The string builder to append text to
	 * @param current
	 *            The number of seconds in the duration
	 * @param duration
	 *            The number of seconds in this component
	 * @param name
	 *            The name of this component
	 * @return The number of seconds used by this component
	 */
	private static long doDuration(final StringBuilder builder,
			final long current, final long duration, final String name) {
		long res = 0;

		if (current >= duration) {
			final long units = current / duration;
			res = units * duration;

			if (builder.length() > 0) {
				builder.append(", ");
			}

			builder.append(units);
			builder.append(' ');
			builder.append(name);
			builder.append(units == 1 ? "" : 's');
		}

		return res;
	}

	/**
	 * Formats the specified number of seconds as a string containing the number
	 * of days, hours, minutes and seconds.
	 * 
	 * @param duration
	 *            The duration in seconds to be formatted
	 * @return A textual version of the duration
	 */
	public static String formatDuration(final long duration) {
		final StringBuilder buff = new StringBuilder();

		long seconds = duration;

		seconds -= doDuration(buff, seconds, 60 * 60 * 24, "day");
		seconds -= doDuration(buff, seconds, 60 * 60, "hour");
		seconds -= doDuration(buff, seconds, 60, "minute");
		doDuration(buff, seconds, 1, "second");

		return buff.length() == 0 ? "0 seconds" : buff.toString();
	}
}
