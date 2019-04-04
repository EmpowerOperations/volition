using System;
using Google.Protobuf.Collections;

namespace EmpowerOps.Volition.RefClient
{
    public class EvaluationResult
    {
        public enum ResultStatus
        {
            Succeed, Failed, Canceled
        }

        public ResultStatus Status { get; set; }
        public Exception Exception { get; set; }
       
        public MapField<string, double> Output { get; set; }
        public MapField<string, double> Input { get; set; }
    }

}
