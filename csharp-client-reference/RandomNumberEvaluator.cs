using System;
using System.Collections;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace EmpowerOps.Volition.RefClient
{
    public class RandomNumberEvaluator : IEvaluator
    {
        private CancellationTokenSource _evaluationCancellationTokenSource;
        private bool _failToggle = false;

        public void Cancel()
        {
            _evaluationCancellationTokenSource.Cancel();
        }

        public async Task<EvaluationResult> EvaluateAsync(IDictionary<string, double> inputs, IList outputs)
        {
            _evaluationCancellationTokenSource = new CancellationTokenSource();

            try
            {
                return await EvaluationAsync(inputs, outputs, _evaluationCancellationTokenSource.Token);
            }
            catch (OperationCanceledException)
            {
                return new EvaluationResult { Input = inputs, Output = new Dictionary<string, double>(), Status = EvaluationResult.ResultStatus.Canceled };
            }
            catch (EvaluationException e)
            {
                var result = new Dictionary<string, double>();
                foreach (Output output in outputs)
                {
                    var evaluationResult = Double.PositiveInfinity;
                    result.Add(output.Name, evaluationResult);
                }

                return new EvaluationResult
                {
                    Input = inputs,
                    Output = result,
                    Status = EvaluationResult.ResultStatus.Failed,
                    Exception = e
                };
            }
            catch (Exception e)
            {
                return new EvaluationResult
                {
                    Input = inputs,
                    Output = new Dictionary<string, double>(),
                    Status = EvaluationResult.ResultStatus.Failed,
                    Exception = e
                };
            }
        }

        private async Task<EvaluationResult> EvaluationAsync(IDictionary<string, double> inputs, IList outputs, CancellationToken ct)
        {
            await Task.Delay(1000);
            ct.ThrowIfCancellationRequested();
            if (_failToggle)
            {
                _failToggle = false;
                throw new EvaluationException($"Error evaluating {inputs}");
            }
            await Task.Delay(4000);
            ct.ThrowIfCancellationRequested();
            var result = new Dictionary<string, double>();
            var random = new Random();
           
            foreach (Output output in outputs)
            {
                var evaluationResult = random.NextDouble();
                result.Add(output.Name, evaluationResult);
            }

            return new EvaluationResult { Input = inputs, Output = result, Status = EvaluationResult.ResultStatus.Succeed };
        }

        public void SetFailNext()
        {
            _failToggle = true;
        }
    }

}
