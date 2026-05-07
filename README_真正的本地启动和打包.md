# 真正的启动
## 1、后端启动
```shell
cd backend/console/target
  java \
    -Dhigress-console.kube-config=/Users/zengyr7/Downloads/cls-en1 \
    -Dhigress-console.ns=higress-for-wgb-uat \
    -Dhigress-console.controller.watched-namespace=higress-for-wgb-uat \
    -Dhigress-console.controller.service.host=higress-controller.higress-for-wgb-uat.svc.cluster.local \
    -Dhigress-console.controller.service.port=15014 \
    -jar higress-console.jar
```
以上也可以放到application properties里面
### 新增注意点，2.2.1以后，增强了鉴权限制，access-token要改为用下面这个方式获取.token有效期30天
```shell
kubectl --kubeconfig=/Users/zengyr7/Downloads/cls-en1 -n higress-for-wgb-uat \                                                                                                                                                   
    create token a42a408ba116a44d1-higress-controller \                                                                                                                                                                            
    --audience=istio-ca --duration=720h
```


## 2、前端启动
```shell
cd frontend
npm start
```


# 真正的打包
## 1、注意项目
```plantuml
1、整个项目代码不能有任何中文
2、业务修改要符合业务板块，比如providerId的使用和改动，不能在matchrule中做，只能在provider的实现类中做。反正就是业务解耦的意思；
3、构建不成功就删掉target，clean，再重新打包！
```
## 2、打包
有个比较离谱的点：你要先手动执行
```shell
mvn clean package -Dmaven.test.skip=true
```
然后再
```shell
cd backend
sh build.sh
```
基本就是clean package，外加docker build。不知道是Idea的问题还是咋，每次改动完代码都要像上面这样弄才行。即便build.sh里面已经有了package命令。

## 3、上传到midea镜像
```shell
docker tag <Image Id> image.midea.com/midea-middleware/higress/higress-console:2.1.9-midea
docker push image.midea.com/midea-middleware/higress/higress-console:2.1.9-midea
```
