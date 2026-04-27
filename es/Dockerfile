ARG STACK_VERSION
FROM docker.elastic.co/elasticsearch/elasticsearch:${STACK_VERSION}
RUN bin/elasticsearch-plugin install --batch analysis-nori
