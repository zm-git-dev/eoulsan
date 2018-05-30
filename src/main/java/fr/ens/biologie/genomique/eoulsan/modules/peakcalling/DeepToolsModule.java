package fr.ens.biologie.genomique.eoulsan.modules.peakcalling;

import static fr.ens.biologie.genomique.eoulsan.EoulsanLogger.getLogger;
import static fr.ens.biologie.genomique.eoulsan.data.DataFormats.MAPPER_RESULTS_BAM;
import static fr.ens.biologie.genomique.eoulsan.data.DataFormats.MAPPER_RESULTS_INDEX_BAI;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Joiner;

import fr.ens.biologie.genomique.eoulsan.EoulsanException;
import fr.ens.biologie.genomique.eoulsan.Globals;
import fr.ens.biologie.genomique.eoulsan.annotations.LocalOnly;
import fr.ens.biologie.genomique.eoulsan.core.InputPorts;
import fr.ens.biologie.genomique.eoulsan.core.InputPortsBuilder;
import fr.ens.biologie.genomique.eoulsan.core.Modules;
import fr.ens.biologie.genomique.eoulsan.core.Parameter;
import fr.ens.biologie.genomique.eoulsan.core.StepConfigurationContext;
import fr.ens.biologie.genomique.eoulsan.core.TaskContext;
import fr.ens.biologie.genomique.eoulsan.core.TaskResult;
import fr.ens.biologie.genomique.eoulsan.core.TaskStatus;
import fr.ens.biologie.genomique.eoulsan.core.Version;
import fr.ens.biologie.genomique.eoulsan.data.Data;
import fr.ens.biologie.genomique.eoulsan.data.DataFile;
import fr.ens.biologie.genomique.eoulsan.data.DataFormat;
import fr.ens.biologie.genomique.eoulsan.data.DataFormatRegistry;
import fr.ens.biologie.genomique.eoulsan.design.Design;
import fr.ens.biologie.genomique.eoulsan.design.DesignUtils;
import fr.ens.biologie.genomique.eoulsan.design.Experiment;
import fr.ens.biologie.genomique.eoulsan.design.ExperimentSample;
import fr.ens.biologie.genomique.eoulsan.modules.AbstractModule;
import fr.ens.biologie.genomique.eoulsan.requirements.DockerRequirement;
import fr.ens.biologie.genomique.eoulsan.requirements.Requirement;
import fr.ens.biologie.genomique.eoulsan.util.ProcessUtils;
import fr.ens.biologie.genomique.eoulsan.util.process.DockerManager;
import fr.ens.biologie.genomique.eoulsan.util.process.SimpleProcess;

/**
 * This class uses tools from the DeepTools suite.
 * @author Celine Hernandez - CSB lab - ENS - Paris
 * @author Cedric Michaud - CSB lab - ENS - Paris
 */
@LocalOnly
public class DeepToolsModule extends AbstractModule {

  /** Module name */
  private static final String MODULE_NAME = "deeptools";

  private static DataFormat PEAK =
      DataFormatRegistry.getInstance().getDataFormatFromName("peaks");

  private static final String DEEPTOOLS_DEFAULT_DOCKER_IMAGE =
      "genomicpariscentre/deeptools:2.2.4";

  private Requirement requirement;

  private String dockerImage = DEEPTOOLS_DEFAULT_DOCKER_IMAGE;

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @Override
  public String getDescription() {
    return "This step runs QC tools from the DeepTools suite.";
  }

  @Override
  public Version getVersion() {
    return Globals.APP_VERSION;
  }

  @Override
  public InputPorts getInputPorts() {
    final InputPortsBuilder builder = new InputPortsBuilder();
    builder.addPort("inputpeak", true, PEAK);
    builder.addPort("inputbamlist", true, MAPPER_RESULTS_BAM);
    builder.addPort("inputbailist", true, MAPPER_RESULTS_INDEX_BAI);
    return builder.create();
  }

  /**
   * Set the parameters of the step to configure the step.
   * @param stepParameters parameters of the step
   * @throws EoulsanException if a parameter is invalid
   */
  @Override
  public void configure(final StepConfigurationContext context,
      final Set<Parameter> stepParameters) throws EoulsanException {

    for (Parameter p : stepParameters) {

      switch (p.getName()) {

      case "docker.image":

        this.dockerImage = p.getStringValue().trim();
        if (this.dockerImage.isEmpty()) {
          Modules.badParameterValue(context, p,
              "The docker image name is empty");
        }
        break;

      default:
        Modules.unknownParameter(context, p);
        break;
      }

    }

    this.requirement =
        DockerRequirement.newDockerRequirement(dockerImage, true);
  }

  @Override
  public Set<Requirement> getRequirements() {
    return Collections.singleton(this.requirement);
  }

  /**
   * Run deeptools.
   */
  @Override
  public TaskResult execute(final TaskContext context,
      final TaskStatus status) {

    // Get peaks *list* data (PEAK format, as generated by a peak caller)
    final Data peaksData = context.getInputData(PEAK);

    final Design design = context.getWorkflow().getDesign();

    // Create a hashmap to containt PEAK data and the experiment corresponding
    // to thoses data as a key.
    Map<String, Data> nameMapPEAK = new HashMap<>();

    for (Data anInputData : peaksData.getListElements()) {

      String name = anInputData.getMetadata().getSampleName();
      nameMapPEAK.put(name, anInputData);

      for (Experiment e : design.getExperiments()) {
        for (ExperimentSample expSam : e.getExperimentSamples()) {
          if (nameMapPEAK.get(expSam.getSample().getName()) != null) {
            nameMapPEAK.remove(name);
            nameMapPEAK.put(e.getName(), anInputData);
          }
        }
      }
    }

    // String for the concatenated BAM files paths/labels
    List<String> bamFileNames = new ArrayList<>();
    List<String> bamFileLabels = new ArrayList<>();

    // Get bam *list* (BAM format, as generated by a mapper)
    final Data mappedData = context.getInputData(MAPPER_RESULTS_BAM);

    // Create a hashmap to contain BAM data and the corresponding sample name as
    // a key.
    Map<String, Data> nameMapBAM = new HashMap<>();
    for (Data anInputData : mappedData.getListElements()) {
      String name = anInputData.getMetadata().getSampleName();
      nameMapBAM.put(name, anInputData);
    }

    // Get bai *list* (BAI format, as generated by sam2bam)
    final Data indexData = context.getInputData(MAPPER_RESULTS_INDEX_BAI);

    // Create a hashmap to contain BAI data and the corresponding sample name as
    // a key.
    Map<String, Data> nameMapBAI = new HashMap<>();
    for (Data anInputData : indexData.getListElements()) {
      String name = anInputData.getMetadata().getSampleName();
      nameMapBAI.put(name, anInputData);
    }

    for (Experiment e : design.getExperiments()) {

      for (ExperimentSample expSam : e.getExperimentSamples()) {

        getLogger().info(
            String.format("BAM Experiment %s - Condition %s - RepTechGroup %s",
                e.getName(), DesignUtils.getCondition(expSam),
                DesignUtils.getRepTechGroup(expSam)));

        final String bamFileName;

        // Test to see if the corresponding experiment sample exists (Useful
        // because of the replicates merge).
        if (nameMapBAM.get(expSam.getSample().getName()) != null) {
          bamFileName =
              nameMapBAM.get(expSam.getSample().getName()).getDataFilename();
          bamFileNames.add(bamFileName);
        } else {
          continue;
        }

        bamFileLabels.add(DesignUtils.getCondition(expSam)
            + DesignUtils.getRepTechGroup(expSam));

        // Find the corresponding BAI file
        // This should be replaced when a more convenient way to link BAM/BAI
        // files will be available in Eoulsan

        // Get bai *list* (BAI format, as generated by sam2bam)
        // final Data indexData =
        // context.getInputData(MAPPER_RESULTS_INDEX_BAI);
        // Loop through all indexes to find the one corresppnding to current
        // sample
        for (ExperimentSample expSam2 : e.getExperimentSamples()) {

          // Compare to BAM information
          if (nameMapBAI.get(expSam.getSample().getName()) != null
              && DesignUtils.getCondition(expSam2)
                  .equals(DesignUtils.getCondition(expSam))
              && DesignUtils.getRepTechGroup(expSam2)
                  .equals(DesignUtils.getRepTechGroup(expSam))) {

            getLogger().info("BAI File: "
                + nameMapBAI.get(expSam.getSample().getName())
                    .getDataFilename());

            // Create a symlink for this BAI file.
            try {
              final DataFile oldName = new DataFile(nameMapBAI
                  .get(expSam.getSample().getName()).getDataFilename());

              final DataFile newName = new DataFile(bamFileName + ".bai");
              getLogger().info("DataFile newName = " + newName);
              if (!newName.exists()) {
                getLogger().info("DataFile BAI : " + oldName);
                oldName.symlink(newName);
              }
            } catch (java.io.IOException err) {
              err.printStackTrace();
              getLogger().severe(err.getMessage());
              return status.createTaskResult();
            }

            break;
          }
        }
      }

      // Create the docker process to use
      final SimpleProcess process;

      try {
        process = DockerManager.getInstance().createImageInstance(dockerImage);
      } catch (IOException err) {
        return status.createTaskResult(err);
      }

      // Build command line to generate the multibamSummary file.
      List<String> cmd1multibamSummary = new ArrayList<String>();

      // Executable
      cmd1multibamSummary.add("multiBamSummary");
      cmd1multibamSummary.add("bins");

      // BAM files
      cmd1multibamSummary.add("--bamfiles");
      for (String element : bamFileNames) {
        cmd1multibamSummary.add(element);
      }
      cmd1multibamSummary.add("--labels");
      for (String element : bamFileLabels) {
        cmd1multibamSummary.add(element);
      }

      // Construct output
      final String multiBamSummaryOutput =
          "multiBamSummary_readCounts_" + e.getName() + "%s.npz";

      cmd1multibamSummary.add("-o");
      cmd1multibamSummary.add(multiBamSummaryOutput);

      cmd1multibamSummary.add("--outRawCounts");
      cmd1multibamSummary
          .add("multiBamSummary_readCounts_" + e.getName() + "%s.tab");

      final File stderrFile1 =
          new File("dockerMultiBAMSummary_global_" + e.getName() + ".err");
      final File stdoutFile1 =
          new File("dockerMultiBAMSummary_global_" + e.getName() + ".out");

      // Execute process
      try {
        final int exitValue1 = process.execute(cmd1multibamSummary,
            context.getStepOutputDirectory().toFile(),
            context.getLocalTempDirectory(), stdoutFile1, stderrFile1);

        ProcessUtils.throwExitCodeException(exitValue1,
            Joiner.on(' ').join(cmd1multibamSummary));
      } catch (IOException err) {
        return status.createTaskResult(err);
      }

      // Build command line for bamCorrelate: whole genome
      List<String> cmd2bamCorrelate = new ArrayList<String>();

      // Executable
      cmd2bamCorrelate.add("plotCorrelation");
      cmd2bamCorrelate.add("--whatToPlot");
      cmd2bamCorrelate.add("heatmap");

      // BAM files
      cmd2bamCorrelate.add("--plotNumbers");
      cmd2bamCorrelate.add("-in");
      cmd2bamCorrelate.add(multiBamSummaryOutput);

      // Parameters
      cmd2bamCorrelate.add("--corMethod");
      cmd2bamCorrelate.add("spearman");
      cmd2bamCorrelate.add("--colorMap");
      cmd2bamCorrelate.add("Blues");
      cmd2bamCorrelate.add("--zMin");
      cmd2bamCorrelate.add("0");
      cmd2bamCorrelate.add("--zMax");
      cmd2bamCorrelate.add("1");

      // Output file
      cmd2bamCorrelate.add("-o");
      cmd2bamCorrelate
          .add("bamcorrelatebins_output_report_" + e.getName() + ".pdf");

      final File stderrFile2 =
          new File("dockerPlotCorrelation_global_" + e.getName() + ".err");
      final File stdoutFile2 =
          new File("dockerPlotCorrelation_global_" + e.getName() + ".out");

      // Execute process
      try {
        final int exitValue2 = process.execute(cmd2bamCorrelate,
            context.getStepOutputDirectory().toFile(),
            context.getLocalTempDirectory(), stdoutFile2, stderrFile2);

        ProcessUtils.throwExitCodeException(exitValue2,
            Joiner.on(' ').join(cmd2bamCorrelate));
      } catch (IOException err) {
        return status.createTaskResult(err);
      }

      if (nameMapPEAK.containsKey(e.getName())) {

        // Build command line to generate the second multibamSummary file.

        List<String> cmd3multibamSummary = new ArrayList<String>();

        // Executable
        cmd3multibamSummary.add("multiBamSummary");
        cmd3multibamSummary.add("BED-file");
        cmd3multibamSummary.add("--BED");
        cmd3multibamSummary.add(nameMapPEAK.get(e.getName()).getDataFilename());

        // BAM files
        cmd3multibamSummary.add("--bamfiles");
        for (String element : bamFileNames) {
          cmd3multibamSummary.add(element);
        }
        cmd3multibamSummary.add("--labels");
        for (String element : bamFileLabels) {
          cmd3multibamSummary.add(element);
        }

        // Construct output
        final String multiBamSummaryOutput2 =
            "multiBamSummary_peaks_readCounts_" + e.getName() + "%s.npz";

        cmd3multibamSummary.add("-o");
        cmd3multibamSummary.add(multiBamSummaryOutput2);

        cmd3multibamSummary.add("--outRawCounts");
        cmd3multibamSummary
            .add("multiBamSummary_peaks_readCounts_" + e.getName() + ".tab");

        final File stderrFile3 =
            new File("dockerMultiBAMSummary_peak_" + e.getName() + ".err");
        final File stdoutFile3 =
            new File("dockerMultiBAMSummary_peak_" + e.getName() + ".out");

        // Execute process
        try {
          final int exitValue3 = process.execute(cmd3multibamSummary,
              context.getStepOutputDirectory().toFile(),
              context.getLocalTempDirectory(), stdoutFile3, stderrFile3);

          ProcessUtils.throwExitCodeException(exitValue3,
              Joiner.on(' ').join(cmd3multibamSummary));
        } catch (IOException err) {
          return status.createTaskResult(err);
        }

        // Build command line for bamCorrelate: bed file used
        List<String> cmd4bamCorrelate = new ArrayList<String>();

        // Executable
        cmd4bamCorrelate.add("plotCorrelation");
        cmd4bamCorrelate.add("--whatToPlot");
        cmd4bamCorrelate.add("heatmap");

        // BAM files
        cmd4bamCorrelate.add("--plotNumbers");
        cmd4bamCorrelate.add("-in");
        cmd4bamCorrelate.add(multiBamSummaryOutput2);

        // Parameters
        cmd4bamCorrelate.add("--corMethod");
        cmd4bamCorrelate.add("spearman");
        cmd4bamCorrelate.add("--colorMap");
        cmd4bamCorrelate.add("Reds");
        cmd4bamCorrelate.add("--zMin");
        cmd4bamCorrelate.add("0");
        cmd4bamCorrelate.add("--zMax");
        cmd4bamCorrelate.add("1");

        // Output file
        cmd4bamCorrelate.add("-o");
        cmd4bamCorrelate
            .add("bamcorrelatepeaks_output_report_" + e.getName() + ".pdf");

        final File stderrFile4 =
            new File("dockerPlotCorrelation_peak_" + e.getName() + ".err");
        final File stdoutFile4 =
            new File("dockerPlotCorrelation_peak_" + e.getName() + ".out");

        // Execute process

        try {
          final int exitValue4 = process.execute(cmd4bamCorrelate,
              context.getStepOutputDirectory().toFile(),
              context.getLocalTempDirectory(), stdoutFile4, stderrFile4);

          ProcessUtils.throwExitCodeException(exitValue4,
              Joiner.on(' ').join(cmd4bamCorrelate));
        } catch (IOException err) {
          return status.createTaskResult(err);
        }

      }

      // Build command line for bamFingerprint: whole genome

      List<String> cmd5bamFingerprint = new ArrayList<String>();

      // Executable
      cmd5bamFingerprint.add("plotFingerprint");
      // BAM files
      cmd5bamFingerprint.add("--bamfiles");
      for (String element : bamFileNames) {
        cmd5bamFingerprint.add(element);
      }
      cmd5bamFingerprint.add("--labels");
      for (String element : bamFileLabels) {
        cmd5bamFingerprint.add(element);
      }

      // Output file
      cmd5bamFingerprint.add("--plotFile");
      cmd5bamFingerprint
          .add("bamfingerprint_output_report_" + e.getName() + ".pdf");
      cmd5bamFingerprint.add("--plotFileFormat");
      cmd5bamFingerprint.add("pdf");

      final File stderrFile5 =
          new File("dockerFingerPrint_" + e.getName() + ".err");
      final File stdoutFile5 =
          new File("dockerFingerPrint_" + e.getName() + ".out");

      // Execute process
      try {
        final int exitValue5 = process.execute(cmd5bamFingerprint,
            context.getStepOutputDirectory().toFile(),
            context.getLocalTempDirectory(), stdoutFile5, stderrFile5);

        ProcessUtils.throwExitCodeException(exitValue5,
            Joiner.on(' ').join(cmd5bamFingerprint));
      } catch (IOException err) {
        return status.createTaskResult(err);
      }

    }

    return status.createTaskResult();
  }
}
