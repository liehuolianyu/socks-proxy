FROM crpi-5p14bsv1qryxc5zf.cn-chengdu.personal.cr.aliyuncs.com/yangge6666/maven:3.6.3-jdk-8
WORKDIR /build
#将代码仓库拷贝至工作目录
COPY . .
RUN mvn install
