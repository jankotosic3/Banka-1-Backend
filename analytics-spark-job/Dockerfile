FROM apache/spark:3.5.3-python3

WORKDIR /opt/spark/work-dir

USER root
RUN python3 -m pip install --no-cache-dir numpy==1.24.4

RUN curl -fsSL \
    "https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar" \
    -o /opt/spark/jars/postgresql-42.7.4.jar

COPY app/analytics_job.py /opt/spark/work-dir/analytics_job.py

USER 185
