package fr.ens.biologie.genomique.eoulsan.modules.chipseq.peakcalling;

import static fr.ens.biologie.genomique.eoulsan.EoulsanLogger.getLogger;
import static fr.ens.biologie.genomique.eoulsan.modules.chipseq.ChIPSeqDataFormats.PEAK;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import fr.ens.biologie.genomique.eoulsan.Globals;
import fr.ens.biologie.genomique.eoulsan.annotations.LocalOnly;
import fr.ens.biologie.genomique.eoulsan.core.InputPorts;
import fr.ens.biologie.genomique.eoulsan.core.InputPortsBuilder;
import fr.ens.biologie.genomique.eoulsan.core.Parameter;
import fr.ens.biologie.genomique.eoulsan.core.StepConfigurationContext;
import fr.ens.biologie.genomique.eoulsan.core.TaskContext;
import fr.ens.biologie.genomique.eoulsan.core.TaskResult;
import fr.ens.biologie.genomique.eoulsan.core.TaskStatus;
import fr.ens.biologie.genomique.eoulsan.core.Version;
import fr.ens.biologie.genomique.eoulsan.data.Data;
import fr.ens.biologie.genomique.eoulsan.modules.AbstractModule;
import fr.ens.biologie.genomique.eoulsan.util.ProcessUtils;

/**
 * This class defines the peak merging step. It merges peak files created by a
 * peak caller into one BED file.
 * @author Pierre-Marie Chiaroni - CSB lab - ENS - Paris
 * @author Celine Hernandez - CSB lab - ENS - Paris
 */
@LocalOnly
public class MergePeaksModule extends AbstractModule {

  @Override
  public String getName() {
    return "mergepeaks";
  }

  @Override
  public String getDescription() {
    return "This step concatenante all the peaks files of each RepTechGroup.";
  }

  @Override
  public Version getVersion() {
    return Globals.APP_VERSION;
  }

  @Override
  public InputPorts getInputPorts() {
    final InputPortsBuilder builder = new InputPortsBuilder();
    builder.addPort("inputpeaklists", true, PEAK);
    return builder.create();
  }

  /**
   * Set the parameters of the step to configure the step. Nothing to configure
   * for such a simple step.
   * @param stepParameters parameters of the step
   */
  @Override
  public void configure(final StepConfigurationContext context,
      final Set<Parameter> stepParameters) {

    // No parameters
    if (!stepParameters.isEmpty()) {
      getLogger().warning("MergePeaks accepts no parameters. Ignored.");
    }

  }

  @Override
  public TaskResult execute(final TaskContext context,
      final TaskStatus status) {

    // Get input data (PEAK format, as generated by )
    final Data inData = context.getInputData(PEAK);

    // First sort data into experiments/replicate groups before we can
    // concatenate what is inside each group
    HashMap<String, ArrayList<Data>> expMap =
        new HashMap<>(inData.getListElements().size() / 2);
    for (Data anInputData : inData.getListElements()) {

      getLogger().finest("Input file. ref : "
          + anInputData.getMetadata().get("Reference") + "| exp : "
          + anInputData.getMetadata().get("Experiment") + "| rep : "
          + anInputData.getMetadata().get("RepTechGroup"));

      boolean isReference = anInputData.getMetadata().get("Reference")
          .toLowerCase().equals("true");

      // if we have a control, add it along with the experiment name
      if (isReference) {
        getLogger().finest("Reference file, not treated.");
        continue;
      }
      getLogger().finest("Not a reference file. Proceeding.");

      String experimentName = anInputData.getMetadata().get("Experiment");
      String replicateGroupName = anInputData.getMetadata().get("RepTechGroup");

      // if we have a sample
      String sortingKey = experimentName + replicateGroupName;
      if (expMap.get(sortingKey) == null) {
        ArrayList<Data> tmpList = new ArrayList<>();
        tmpList.add(anInputData);
        expMap.put(sortingKey, tmpList);
      } else {
        expMap.get(sortingKey).add(anInputData);
      }
      getLogger().finest("Now "
          + expMap.get(sortingKey).size() + " samples for experiment/replicate "
          + sortingKey);
    }

    // Loop through each experiment
    // We want to find the
    for (String experimentName : expMap.keySet()) {

      // Get all samples of current experiment/replicate group
      ArrayList<Data> expDataList = expMap.get(experimentName);

      if (expDataList.size() < 2) {
        continue;
      }

      StringBuilder cmd = new StringBuilder("cat");
      for (Data sample : expDataList) {
        cmd.append(String.format(" %s", sample.getDataFile().getSource()));
      }

      try {
        File outputFile = new File(String.format("%s/mergedpeaks_output_%s.bed",
            expDataList.get(0).getDataFile().getParent().getSource(),
            experimentName));

        getLogger().info(String.format("Running : %s with output: %s",
            cmd.toString(), outputFile));
        ProcessUtils.execWriteOutput(cmd.toString(), outputFile);

      } catch (java.io.IOException e) {
        getLogger().severe(e.toString());
      }

    }

    return status.createTaskResult();
  }

}
