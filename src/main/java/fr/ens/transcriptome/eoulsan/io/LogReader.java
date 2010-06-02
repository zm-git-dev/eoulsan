/*
 *                      Nividic development code
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  If you do not have a copy,
 * see:
 *
 *      http://www.gnu.org/copyleft/lesser.html
 *
 * Copyright for this code is held jointly by the microarray platform
 * of the École Normale Supérieure and the individual authors.
 * These should be listed in @author doc comments.
 *
 * For more information on the Nividic project and its aims,
 * or to join the Nividic mailing list, visit the home page
 * at:
 *
 *      http://www.transcriptome.ens.fr/nividic
 *
 */

package fr.ens.transcriptome.eoulsan.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import fr.ens.transcriptome.eoulsan.util.FileUtils;
import fr.ens.transcriptome.eoulsan.util.Reporter;

/**
 * This class define a log reader that store log data in a Reporter object
 * @author Laurent Jourdren
 */
public class LogReader {

  private BufferedReader reader;

  /**
   * Read a log file.
   * @return a reporter object
   * @throws IOException if an error occurs while reading data
   */
  public Reporter read() throws IOException {

    final Reporter result = new Reporter();

    String line = null;
    String counterGroup = null;

    while ((line = reader.readLine()) != null) {

      final String tLine = line.trim();

      if ("".equals(tLine)
          || tLine.startsWith("Start time:") || tLine.startsWith("End time:")
          || tLine.startsWith("Duration:"))
        continue;

      if (line.startsWith("\t")) {

        if (counterGroup == null)
          continue;

        final int separatorIndex = tLine.indexOf('=');
        if (separatorIndex == -1)
          continue;
        final String counter = tLine.substring(0, separatorIndex);

        try {
          final int value =
              Integer.parseInt(tLine.substring(separatorIndex + 1));

          result.setCounter(counterGroup, counter, value);
        } catch (NumberFormatException e) {
          continue;
        }
      } else
        counterGroup = line;

    }

    this.reader.close();

    return result;
  }

  //
  // Constructors
  //

  /**
   * Public constructor
   * @param is InputStream to use
   */
  public LogReader(final InputStream is) {

    if (is == null)
      throw new NullPointerException("InputStream is null");

    this.reader = new BufferedReader(new InputStreamReader(is));
  }

  /**
   * Public constructor
   * @param file File to use
   */
  public LogReader(final File file) throws FileNotFoundException {

    if (file == null)
      throw new NullPointerException("File is null");

    if (!file.isFile())
      throw new FileNotFoundException("File not found: "
          + file.getAbsolutePath());

    this.reader = FileUtils.createBufferedReader(file);
  }

  public static final void main(String[] args) throws FileNotFoundException,
      IOException {

    System.out
        .println(new LogReader(
            new File(
                "/home/jourdren/shares-mimir/bioinfo/test-soap/lolo-local/soapmapreads.log"))
            .read().toString());

  }

}
