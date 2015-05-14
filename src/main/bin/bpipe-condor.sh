#!/bin/bash
# vim: expandtab:ts=4

# Start, stop and get status of jobs running on a Condor job scheduler.
#
# Usage:
#
# Starting a job (will print job ID on standard output):
#
#    COMMAND="foobar" NAME=test WALLTIME="00:01:00" PROCS=1 QUEUE=main JOBTYPE=single ./bpipe-condor.sh start
#
# Stopping a job (given some job id "my_job_id")
#
#    ./bpipe-condor.sh stop my_job_id
#
# Getting the status of a job (given some job id "my_job_id")
#
#    ./bpipe-condor.sh status my_job_id
#
# Notes:
#
# None of the commands are guaranteed to succeed. An exit status of 0 for this script
# indicates success, all other exit codes indicate failure (see below).
#
# Stopping a job may not cause it to stop immediately. You are advised to check the
# status of a job after asking it to stop. You may need to poll this value.
#
# We are not guaranteed to know the exit status of a job command, for example if the
# job was killed before the command was run.
#
# Authors: Bernie Pope, Simon Sadedin, Alicia Oshlack
# Copyright 2011.

################################################################################
# Modified 2013
# Andrew Lonsdale

# Modified from bpipe-torque.sh
# Called from SlurmCommandExecutor.groovy, based on TorqueCommandExecutor.groovy 
#
# Approach is mimic the wrapper and shell script relationship, and replace 
# Torque commands with Slurm equivalents

################################################################################
################################################################################
# Modified 2014
# Simon Gladman
#
# Added support for modules in Slurm. (Give the config file a space separated
# list of required modules using the modules="module1 module2 ..." directive.)
#
# Example of required format in config file:
#
#   commands {
#   
#       //quick jobs 10 min and 4GB
#       small {
#           walltime="0:10:00"
#           memory="4"
#           modules="python-gcc/2.7.5 qiime-gcc/1.8.0"
#       }
#   }
#       
#   The modifications then alter the Slurm script produced by bpipe to add the 
#   correct "module load XXXX/x.x.x" directives at the appropriate place.
#
################################################################################

# This is what we call the program in user messages
program_name=bpipe-condor

# exit codes:
SUCCESS=0
INCORRECT_FIRST_ARGUMENT=1 # must be start, stop, or status
MISSING_JOB_PARAMETER=2    # one of the env vars not defined
STOP_MISSING_JOBID=3       # stop command not given job id as parameter
STATUS_MISSING_JOBID=4     # status command not given job id as parameter
CONDOR_RM_FAILED=5              # scancel command returned non-zero exit status
CONDOR_Q_FAILED=6             # scontrol command returned non-zero exit status
CONDOR_QSUB_FAILED=7              # sbatch command returned non-zero exit status
MKDIR_JOBDIR_FAILED=8
JOBTYPE_FAILED=9              # jobtype variable led to non-zero exit status

ESSENTIAL_ENV_VARS="COMMAND NAME"
OPTIONAL_ENV_VARS="WALLTIME PROCS QUEUE JOBDIR JOBTYPE MEMORY"
DEFAULT_BATCH_MEM=4096
DEFAULT_BATCH_PROCS=1
DEFAULT_WALLTIME="01:00:00" # one hour
DEFAULT_QUEUE=debug	#Queue is parition in slurm, will use this with -p
DEFAULT_JOBTYPE=single	#Should be single, smp or mpi


# TODO Use JOBDIR to define where put condor description file

# Print a usage message
usage () {
   echo "usage: $program_name (start | stop ID | status ID)"
   echo "start needs these environment variables: $ESSENTIAL_ENV_VARS"
   echo "start will use these variables if defined: $OPTIONAL_ENV_VARS"
}



# Generate a Condor description file from parameters found in environment variables.
make_condor_description_file () {
   # check that all the essential environment variables are defined
   for v in $ESSENTIAL_ENV_VARS; do
      eval "k=\$$v"
      if [[ -z $k ]]; then
         echo "$program_name ERROR: environment variable $v not defined"
         echo "these environment variables are required: $ESSENTIAL_ENV_VARS"
         exit $MISSING_JOB_PARAMETER
      fi
   done

   # set the walltime
   if [[ -z $WALLTIME ]]; then
      WALLTIME=$DEFAULT_WALLTIME
   fi

   # set the queue
   if [[ -z $QUEUE ]]; then
      QUEUE=$DEFAULT_QUEUE
   fi

  # set the jobtype
   if [[ -z $JOBTYPE ]]; then
      JOBTYPE=$DEFAULT_JOBTYPE
   fi

   # set the job directory if needed
   if [[ -n $JOBDIR ]]; then
      # check if the directory already exists
      if [[ ! -d "$JOBDIR" ]]; then
         # try to make the directory
         mkdir "$JOBDIR"
         # check if the mkdir succeeded
         if [[ $? != 0 ]]; then
            echo "$program_name ERROR: could not create job directory $JOBDIR"
            exit $MKDIR_JOBDIR_FAILED
         fi
      fi
      job_script_name="$JOBDIR/job.condor"
   else
      job_script_name="job.condor"
   fi

   # write out the job script to a file
   # Output masking unreliable at moment, stores the sbatch stdout and stderr in logs
   EXECUTABLE=`echo $COMMAND | cut -f 1 -d ' '`
   ARGUMENTS=`echo $COMMAND | cut -f 2- -d ' '`
   cat > $job_script_name << HERE
Universe = vanilla
Executable = $EXECUTABLE
Output = script.stdout
Error = script.stderr
Arguments = $ARGUMENTS
Queue
HERE

# TODO Handle InitialDir = <path>/test/run_1

   echo $job_script_name
}




# Launch a job on the queue.
start () {
   # create the job script
   job_script_name=`make_condor_description_file`
   # check that the job script file exists
   if [[ -f $job_script_name ]]
      then
         # launch the job and get its id
         job_id_full=`condor_submit -terse $job_script_name`
         condor_submit_exit_status=$?
         if [[ $? -eq 0 ]]
            then
               job_id_number=`echo $job_id_full | tr -s ' ' | cut -f 1 -d ' '`
               echo $job_id_number
            else
               echo "$program_name ERROR: sbatch returned non zero exit status $condor_submit_exit_status"
               exit $CONDOR_QSUB_FAILED
         fi
      else
         echo "$program_name ERROR: could not create job script $job_script_name"
   fi
}

# stop a job given its id
# XXX should we check the status of the job first?
stop () {
   # make sure we have a job id on the command line
   if [[ $# -ge 1 ]]
      then
         # try to stop it
         condor_rm "$1"
         condor_rm_success=$?
         if [[ $condor_rm_success == 0 ]]
            then
               exit $SUCCESS
            else
               exit $CONDOR_RM_FAILED
         fi
      else
         echo "$program_name ERROR: stop requires a job identifier"
         exit $STOP_MISSING_JOBID
   fi
}

# get the status of a job given its id
status () {
   # make sure we have a job id on the command line
   if [[ $# -ge 1 ]]
   then
         # get the output of condor_q
         condor_q_output=`condor_q -better-analyze $1`
         condor_q_success=$?
         if [[ $condor_q_success == 0 ]]
         then
               job_state=`echo $condor_q__output| tail -n +5 | tr -s ' ' | cut -f 6 -d ' '` # JobState is in caps


               if [[ -z "job_state"  ]]
               then
                   job_state=`condor_history $1 | tail -n 1 | tr -s ' ' | cut -f 6 -d ' '`
                   condor_q_success=$?

                   if [[ $condor_q_success -ne 0 ]]
                   then
                        exit $CONDOR_Q_FAILED
                   fi
               fi

               case "$job_state" in
                  H|I) echo WAITING;; 
                  R) echo RUNNING;;    
                  X) echo COMPLETE 999;; # Artificial exit code because Slurm does not provide one    
                  C)
                  # TODO how to get the exit code with Condor 
                  # scontrol will include ExitCode=N:M, where the N is exit code and M is signal (ignored)
                  #        command_exit_status=`echo $scontrol_output |grep Exit|sed 's/.*ExitCode=\([0-9]*\):[0-9]*/\1/'`
                  command_exit_status=`echo $condor_q_output|tr ' ' '\n' |awk -vk="ExitCode" -F"=" '$1~k{ print $2}'|awk -F":" '{print $1}'`

                  # it is possible that command_exit_status will be empty
                  # for example we start the job and then it waits in the queue
                  # and then will kill it without it ever running
                  echo "COMPLETE $command_exit_status";;

                  *) echo UNKNOWN;;
               esac
               exit $SUCCESS

   else
         echo "$program_name ERROR: status requires a job identifier"
         exit $STATUS_MISSING_JOBID
   fi
}

# run the whole thing
main () {
   # check that we have at least one command
   if [[ $# -ge 1 ]]
      then
         case "$1" in
            start)  start;;
            stop)   shift
                      stop "$@";;
            status) shift
                      status "$@";;
            *) usage
               exit $INCORRECT_FIRST_ARGUMENT
            ;;
         esac
      else
         usage
         exit $INCORRECT_FIRST_ARGUMENT
   fi
   exit $SUCCESS
}

main "$@"
