1. `aws cloudformation delete-stack --stack-name` CDKToolkit not deleting the cdk S3 bucket
2. 

Min-DataStack
Min-DataStack: creating CloudFormation changeset...
Changeset arn:aws:cloudformation:us-east-1:491085389857:changeSet/cdk-deploy-change-set/ea0c2ef8-e2c7-4957-8815-262d270cce25 created and waiting in review for manual execution (--no-execute)
Min-DataStack: deploying... [2/8]

Stack ARN:

 ✅  Min-HostingStack

✨  Deployment time: 14.94s

[████████████████████████████████████████▍·················] (37/53)

12:24:40 PM | CREATE_IN_PROGRESS   | AWS::CloudFormation::Stack                    | Min-ComputeS
12:32:46 PM | CREATE_FAILED        | AWS::ECS::Service                             | reServiceF7C
390FA
Resource handler returned message: "Error occurred during operation 'ECS Deployment Circuit Break
er was triggered'." (RequestToken: cd9e52bb-9d8a-25b1-ed15-23a1a5ec4869, HandlerErrorCode: Genera
lServiceException)

12:32:50 PM | DELETE_FAILED        | AWS::ECS::ClusterCapacityProviderAssociations | Cluster3DA9C
CBA
Resource handler returned message: "The specified capacity provider is in use and cannot be remov
ed. (Service: AmazonECS; Status Code: 400; Error Code: ResourceInUseException; Request ID: f89526
57-6a32-4b89-ad95-38a38ecda2a0; Proxy: null)" (RequestToken: b4e99d8b-9135-d790-4015-3c9a7703831d
, HandlerErrorCode: null)

❌  Min-ComputeStack failed: DeploymentError: Resource updates failed:
 └─ Min-ComputeStack
     ├─ Cluster
     │   └─ Cluster  (AWS::ECS::ClusterCapacityProviderAssociations Cluster3DA9CCBA)
     │      🛑 Resource handler returned message: "The specified capacity provider is in use and cannot be removed. (Service:
     │         AmazonECS; Status Code: 400; Error Code: ResourceInUseException; Request ID: f8952657-6a32-4b89-ad95-38a38ecda2a0;
     │         Proxy: null)" (RequestToken: b4e99d8b-9135-d790-4015-3c9a7703831d, HandlerErrorCode: null)
     │      Source Location: ...App.synth in aws-cdk-lib...
     │                       <anonymous> (/Users/beingsuplab/workspace/git/smartretail/infra/cdk-min/bin/app.ts:43:5)
     │                       ...node internals, ts-node...
     └─ reService
         └─ Service  (AWS::ECS::Service reServiceF7C390FA)
            🛑 Resource handler returned message: "Error occurred during operation 'ECS Deployment Circuit Breaker was triggered'."
               (RequestToken: cd9e52bb-9d8a-25b1-ed15-23a1a5ec4869, HandlerErrorCode: GeneralServiceException)
            Source Location: ...new FargateService2 in aws-cdk-lib...
                             ComputeStack.createFargateService (/Users/beingsuplab/workspace/git/smartretail/infra/cdk-min/lib/compute-stack.ts:232:21)
                             new ComputeStack (/Users/beingsuplab/workspace/git/smartretail/infra/cdk-min/lib/compute-stack.ts:171:28)