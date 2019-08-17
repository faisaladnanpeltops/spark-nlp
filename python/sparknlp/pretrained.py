import sparknlp.internal as _internal
import sys
import threading
import time
from pyspark.ml.wrapper import JavaModel
from pyspark.sql import DataFrame
from sparknlp.annotator import *
from sparknlp.base import LightPipeline


def printProgress(stop):
    done = 1
    dot = 7
    while True:
        sys.stdout.write('\r[{}{}]'.format('█' * done, '.' * max(2, dot)))
        sys.stdout.flush()
        time.sleep(2.5)
        done = done + 1
        dot = dot - 1
        if stop():
            sys.stdout.write('\r[{}{}]'.format('█' * done, '█' * max(2, dot)))
            sys.stdout.flush()
            break

    sys.stdout.write('\n')
    return


class ResourceDownloader(object):

    @staticmethod
    def downloadModel(reader, name, language, remote_loc=None):
        print(name + " download started this may take some time")
        stop_threads = False
        t1 = threading.Thread(target=printProgress, args=(lambda: stop_threads,))
        t1.start()
        j_obj = _internal._DownloadModel(reader.name, name, language, remote_loc).apply()
        stop_threads = True
        t1.join()
        print("Download done")
        return reader(classname=None, java_model=j_obj)

    @staticmethod
    def downloadPipeline(name, language, remote_loc=None):
        print(name + " download started this may take some time")
        stop_threads = False
        t1 = threading.Thread(target=printProgress, args=(lambda: stop_threads,))
        t1.start()
        j_obj = _internal._DownloadPipeline(name, language, remote_loc).apply()
        jmodel = JavaModel(j_obj)
        stop_threads = True
        t1.join()
        print("Download done")
        return jmodel

    @staticmethod
    def clearCache(name, language, remote_loc=None):
        _internal._ClearCache(name, language, remote_loc).apply()

    @staticmethod
    def showPublicModels():
        print("test")
        _internal._ShowPublicModels().apply()

    @staticmethod
    def showPublicPipelines():
        _internal._ShowPublicPipelines().apply()


    @staticmethod
    def showUnCategorizedResources():
        _internal._ShowUnCategorizedResources().apply()


class PretrainedPipeline:

    def __init__(self, name, lang='en', remote_loc=None):
        self.model = ResourceDownloader().downloadPipeline(name, lang, remote_loc)
        self.light_model = LightPipeline(self.model)

    def annotate(self, target, column=None):
        if type(target) is DataFrame:
            if not column:
                raise Exception("annotate() column arg needed when targeting a DataFrame")
            return self.model.transform(target.withColumnRenamed(column, "text"))
        elif type(target) is list or type(target) is str:
            pipeline = self.light_model
            return pipeline.annotate(target)
        else:
            raise Exception("target must be either a spark DataFrame, a list of strings or a string")

    def fullAnnotate(self, target, column=None):
        if type(target) is DataFrame:
            if not column:
                raise Exception("annotate() column arg needed when targeting a DataFrame")
            return self.model.transform(target.withColumnRenamed(column, "text"))
        elif type(target) is list or type(target) is str:
            pipeline = self.light_model
            return pipeline.fullAnnotate(target)
        else:
            raise Exception("target must be either a spark DataFrame, a list of strings or a string")

    def transform(self, data):
        return self.model.transform(data)
