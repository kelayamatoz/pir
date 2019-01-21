import os
from util import *

def main():
    (opts, args) = parser.parse_known_args()
    if opts.app is None:
        opts.app = ["*"]
    java_cmd = ""
    java_cmd += "export TEST_ARGS=\"{}\"; ".format(' '.join(args))
    java_cmd += "sbt -Dmaxthreads={} ".format(opts.thread)
    if (opts.backend is not None):
        for b in opts.backend:
            java_cmd += "-Dtest.{}=true ".format(b)
        for p in opts.rerun:
            java_cmd += "-Drerun.{}=true ".format(p)
    else:
        java_cmd += "-Dci=true "
    java_cmd += "\"; "
    java_cmd += " project {}; testOnly {}".format(opts.project, ' '.join(opts.app))
    java_cmd += "\""

    print(java_cmd)

    ecode = os.system(java_cmd)
